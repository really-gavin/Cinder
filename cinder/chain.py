"""Block storage, fork choice and the mempool."""
import json
import os
import threading
import time

from . import consensus as C
from .state import State, Invalid


class Chain:
    def __init__(self, datadir):
        os.makedirs(datadir, exist_ok=True)
        self.path = os.path.join(datadir, f"blocks-{C.CHAIN_ID}.jsonl")
        self.lock = threading.RLock()
        self.blocks = {}      # hash -> block
        self.meta = {}        # hash -> dict(height, prev, time, target, cum)
        self.invalid = set()
        self.orphans = {}
        self.main = []
        self.state = State()
        self.effects = {}     # block hash -> money moved by that block
        self.tx_index = {}    # txid -> (block hash, position)
        self.id_index = {}    # ID -> [(height, kind, ref, extra)]
        self.mempool = {}     # txid -> tx (insertion order = arrival order)
        self.mempool_seen = {}
        self.pending = State()

        g = dict(C.GENESIS)
        g["tx_root"] = C.merkle_root([])
        gh = C.block_hash(g)
        self.genesis_hash = gh
        self.blocks[gh] = g
        t = int(g["target"], 16)
        self.meta[gh] = dict(height=0, prev=None, time=g["time"], target=t, cum=C.block_work(t))
        self.main = [gh]
        self.effects[gh] = []
        self._loading = False
        self._load()
        self._clean_mempool()

    # ------------------------------------------------------------ persistence
    def _load(self):
        if not os.path.exists(self.path):
            return
        with open(self.path) as f:
            lines = [l for l in f if l.strip()]
        self._loading = True
        n = 0
        for line in lines:
            try:
                self.add_block(json.loads(line), persist=False)
                n += 1
            except Exception as e:
                print(f"[chain] skipping stored block: {e}")
        self._loading = False
        print(f"[chain] loaded {n} blocks, height {self.height}")

    def _persist(self, block):
        with open(self.path, "a") as f:
            f.write(C.canonical_json(block) + "\n")

    # ------------------------------------------------------------ helpers
    @property
    def tip(self):
        return self.main[-1]

    @property
    def height(self):
        return len(self.main) - 1

    def ancestors(self, h, n):
        out = []
        while h is not None and len(out) < n:
            m = self.meta[h]
            out.append((m["time"], m["target"]))
            h = m["prev"]
        return out[::-1]

    def median_time_past(self, h):
        times = sorted(t for t, _ in self.ancestors(h, C.MEDIAN_WINDOW))
        return times[len(times) // 2]

    def expected_target(self, parent):
        return C.next_target(self.ancestors(parent, C.RETARGET_WINDOW))

    # ------------------------------------------------------------ blocks
    def check_header(self, b):
        need = {"height", "prev", "time", "target", "miner", "nonce", "note", "tx_root", "txs"}
        if not isinstance(b, dict) or set(b) != need:
            raise Invalid("block has wrong fields")
        if not isinstance(b["txs"], list) or len(b["txs"]) > C.MAX_TXS_PER_BLOCK:
            raise Invalid("bad tx list")
        if not isinstance(b["nonce"], int) or not (0 <= b["nonce"] < 2**64):
            raise Invalid("bad nonce")
        if not isinstance(b["time"], int) or not isinstance(b["height"], int):
            raise Invalid("bad time or height")
        if not isinstance(b["note"], str) or len(b["note"].encode()) > 80 \
                or "|" in b["note"] or any(ord(c) < 32 for c in b["note"]):
            raise Invalid("bad note")
        if not C.is_valid_id(b["miner"]):
            raise Invalid("miner must be a Cinder ID")
        parent = self.meta[b["prev"]]
        if b["height"] != parent["height"] + 1:
            raise Invalid("bad height")
        if not isinstance(b["target"], str) or len(b["target"]) != 64 or \
                int(b["target"], 16) != self.expected_target(b["prev"]) or \
                b["target"] != format(int(b["target"], 16), "064x"):
            raise Invalid("wrong difficulty target")
        if b["time"] <= self.median_time_past(b["prev"]):
            raise Invalid("timestamp too old")
        if b["time"] > time.time() + C.MAX_FUTURE_DRIFT:
            raise Invalid("timestamp too far in the future")
        ids = []
        for tx in b["txs"]:
            try:
                C.check_tx_shape(tx)
            except ValueError as e:
                raise Invalid(f"tx: {e}")
            ids.append(C.txid(tx))
        if len(set(ids)) != len(ids):
            raise Invalid("duplicate tx")
        if C.merkle_root(ids) != b["tx_root"]:
            raise Invalid("tx_root mismatch")
        h = C.block_hash(b)
        if int(h, 16) > int(b["target"], 16):
            raise Invalid("not enough proof of work")
        return h

    def add_block(self, b, persist=True):
        """Returns (status, hash). status: new-tip, side, known, orphan."""
        with self.lock:
            try:
                prev = b["prev"]
                h = C.block_hash(b)
            except Exception:
                raise Invalid("malformed block")
            if prev not in self.meta:
                self.orphans.setdefault(prev, [])
                if len(self.orphans) < 500 and b not in self.orphans[prev]:
                    self.orphans[prev].append(b)
                return "orphan", h
            if prev in self.invalid or h in self.invalid:
                raise Invalid("builds on an invalid block")
            if h in self.meta:
                return "known", h
            h = self.check_header(b)
            target = int(b["target"], 16)
            self.blocks[h] = b
            self.meta[h] = dict(height=b["height"], prev=prev, time=b["time"], target=target,
                                cum=self.meta[prev]["cum"] + C.block_work(target))
            status = "side"
            if self.meta[h]["cum"] > self.meta[self.tip]["cum"]:
                try:
                    self._switch_to(h)
                    status = "new-tip"
                except Invalid:
                    self.invalid.add(h)
                    del self.blocks[h], self.meta[h]
                    raise
            if persist:
                self._persist(b)
            for child in self.orphans.pop(h, []):
                try:
                    self.add_block(child, persist)
                except Invalid:
                    pass
            return status, h

    def _path_to(self, h):
        path = []
        while h is not None:
            path.append(h)
            h = self.meta[h]["prev"]
        return path[::-1]

    def _apply(self, st, h):
        try:
            st.apply_block(self.blocks[h])
        except Invalid as e:
            raise Invalid(f"block {self.blocks[h]['height']}: {e}")
        return st.log

    def _switch_to(self, new_tip):
        if self.meta[new_tip]["prev"] == self.tip:
            st = self.state.copy()
            log = self._apply(st, new_tip)
            self.state = st
            self.main.append(new_tip)
            self.effects[new_tip] = log
            self._index_block(new_tip)
        else:  # reorg: replay from genesis along the heavier branch
            path = self._path_to(new_tip)
            st, effects = State(), {}
            for h in path:
                effects[h] = list(self._apply(st, h))
            keep = set(path)
            dropped = [h for h in self.main if h not in keep]
            self.state, self.main = st, path
            self.effects.update(effects)
            self.tx_index, self.id_index = {}, {}
            for h in path:
                self._index_block(h)
            for h in dropped:
                for tx in self.blocks[h]["txs"]:
                    self.mempool.setdefault(C.txid(tx), tx)
            if not self._loading:
                print(f"[chain] reorg: dropped {len(dropped)} block(s)")
        self._clean_mempool()

    def _note(self, id_, entry):
        if id_:
            lst = self.id_index.setdefault(id_, [])
            if not lst or lst[-1] != entry:
                lst.append(entry)

    def _index_block(self, h):
        """Per-ID history: money moves (effects) plus non-money actions."""
        b = self.blocks[h]
        height = b["height"]
        for i, tx in enumerate(b["txs"]):
            tid = C.txid(tx)
            self.tx_index[tid] = (h, i)
            if tx["type"] == "pay":
                continue  # shows up as a money move below
            self._note(tx["from"], (height, "tx", tid, i))
            ref = tx.get("job") or tx.get("loan")
            thing = (self.state.jobs.get(ref) or self.state.loans.get(ref) or {}) if ref else {}
            for role in ("poster", "worker", "borrower", "lender"):
                if thing.get(role) and thing[role] != tx["from"]:
                    self._note(thing[role], (height, "tx", tid, i))
        for k, e in enumerate(self.effects.get(h, [])):
            if e["why"] == "fee":
                continue
            self._note(e["from"], (height, "effect", h, k))
            self._note(e["to"], (height, "effect", h, k))

    # ------------------------------------------------------------ mempool
    def add_tx(self, tx):
        with self.lock:
            try:
                C.check_tx_shape(tx)
            except ValueError as e:
                raise Invalid(str(e))
            tid = C.txid(tx)
            if tid in self.mempool or tid in self.tx_index:
                return "known", tid
            n = sum(1 for t in self.mempool.values() if t["from"] == tx["from"])
            if n >= C.MAX_PENDING_PER_SENDER:
                raise Invalid("too many pending transactions from this ID; wait for a block")
            if len(self.mempool) >= 20_000:
                raise Invalid("mempool full")
            self.pending.log = []
            self.pending.apply_tx(tx, self.height + 1)   # raises Invalid, changes nothing
            self.mempool[tid] = tx
            self.mempool_seen[tid] = int(time.time())
            return "accepted", tid

    def _clean_mempool(self):
        st = self.state.copy()
        keep = {}
        for tid, tx in self.mempool.items():
            if tid in self.tx_index:
                continue
            try:
                st.apply_tx(tx, self.height + 1)
                keep[tid] = tx
            except Invalid:
                pass
        st.log = []
        self.mempool, self.pending = keep, st
        self.mempool_seen = {k: self.mempool_seen.get(k, int(time.time())) for k in keep}

    # ------------------------------------------------------------ mining
    def block_template(self, miner, note=""):
        with self.lock:
            if miner not in self.pending.accounts:
                raise Invalid("that ID isn't registered yet. Make a wallet first, then mine to it.")
            txs = list(self.mempool.values())[:C.MAX_TXS_PER_BLOCK]
            parent = self.tip
            t = max(int(time.time()), self.median_time_past(parent) + 1)
            return dict(height=self.height + 1, prev=parent, time=t,
                        target=format(self.expected_target(parent), "064x"),
                        miner=miner, nonce=0, note=note, txs=txs,
                        tx_root=C.merkle_root([C.txid(x) for x in txs]))

    def hashrate(self, n=30):
        with self.lock:
            hs = self.main[-n:]
            if len(hs) < 2:
                return 0
            span = self.meta[hs[-1]]["time"] - self.meta[hs[0]]["time"]
            work = sum(C.block_work(self.meta[h]["target"]) for h in hs[1:])
            return work / span if span > 0 else 0
