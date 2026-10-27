#!/usr/bin/env python3
"""Cinder full node.

    python node.py                          # start a node on :8642
    python node.py --peer http://1.2.3.4:8642 --public-url http://me.example:8642

Serves the JSON API (/api/...), the peer protocol (/p2p/...) and the
website (everything else) from one port.
"""
import argparse
import json
import mimetypes
import os
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from cinder import consensus as C
from cinder.chain import Chain, Invalid

ROOT = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.join(ROOT, "website")


class Node:
    def __init__(self, datadir, peers, public_url):
        self.chain = Chain(datadir)
        self.peers = set(p.rstrip("/") for p in peers)
        self.public_url = public_url.rstrip("/") if public_url else None
        self.started = time.time()

    # ------------------------------------------------------------ peer I/O
    def _call(self, peer, path, body=None, timeout=8):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(peer + path, data=data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())

    def broadcast(self, path, body, skip=None):
        def go(p):
            try:
                self._call(p, path, body, timeout=4)
            except Exception:
                pass
        for p in list(self.peers):
            if p != skip:
                threading.Thread(target=go, args=(p,), daemon=True).start()

    def announce_block(self, block, skip=None):
        self.broadcast("/p2p/block", {"block": block, "from": self.public_url}, skip)

    def announce_tx(self, tx, skip=None):
        self.broadcast("/p2p/tx", {"tx": tx, "from": self.public_url}, skip)

    # ------------------------------------------------------------ sync
    def sync_with(self, peer):
        tip = self._call(peer, "/p2p/tip")
        ch = self.chain
        if int(tip["cum"], 16) <= ch.meta[ch.tip]["cum"]:
            return 0
        # find the highest height where we agree
        top = min(ch.height, tip["height"])
        step, fork = 16, 0
        while True:
            lo = max(0, top - step)
            hashes = self._call(peer, f"/p2p/hashes?from={lo}&limit={top - lo + 1}")
            agree = [lo + i for i, h in enumerate(hashes)
                     if lo + i <= ch.height and ch.main[lo + i] == h]
            if agree:
                fork = max(agree)
                break
            if lo == 0:
                raise RuntimeError("peer is on a different genesis")
            step *= 4
        added, h = 0, fork + 1
        while h <= tip["height"]:
            blocks = self._call(peer, f"/p2p/blocks?from={h}&limit=100", timeout=30)
            if not blocks:
                break
            for b in blocks:
                ch.add_block(b)
                added += 1
            h += len(blocks)
        if added:
            print(f"[sync] pulled {added} block(s) from {peer}, height now {ch.height}")
        return added

    def peer_loop(self):
        while True:
            for p in list(self.peers):
                try:
                    if self.public_url:
                        res = self._call(p, "/p2p/hello", {"url": self.public_url})
                        for q in res.get("peers", [])[:32]:
                            if q != self.public_url and len(self.peers) < 64:
                                self.peers.add(q.rstrip("/"))
                    self.sync_with(p)
                except Invalid as e:
                    print(f"[sync] {p} sent an invalid block: {e}")
                except Exception as e:
                    pass
            time.sleep(10)

    # ------------------------------------------------------------ views
    def status(self):
        ch = self.chain
        with ch.lock:
            m, st = ch.meta[ch.tip], ch.state
            return {
                "chain_id": C.CHAIN_ID, "ticker": C.TICKER,
                "height": ch.height, "tip": ch.tip, "tip_time": m["time"],
                "difficulty": C.block_work(m["target"]),
                "hashrate": ch.hashrate(),
                "supply": st.supply,
                "next_reward": C.block_reward(ch.height + 1),
                "mempool": len(ch.mempool),
                "peers": sorted(self.peers),
                "ids": len(st.accounts),
                "open_jobs": sum(1 for j in st.jobs.values() if j["status"] == "open"),
                "open_loans": sum(1 for l in st.loans.values() if l["status"] == "open"),
                "in_escrow": sum(j["pay"] for j in st.jobs.values() if j["status"] in ("open", "taken", "submitted")),
            }

    def block_view(self, h, full=True):
        ch = self.chain
        b, m = ch.blocks[h], ch.meta[h]
        out = {
            "hash": h, "height": b["height"], "prev": b["prev"], "time": b["time"],
            "difficulty": C.block_work(m["target"]), "miner": b["miner"], "nonce": b["nonce"],
            "note": b.get("note", ""), "tx_count": len(b["txs"]),
            "reward": C.block_reward(b["height"]), "fees": sum(t["fee"] for t in b["txs"]),
            "main": b["height"] < len(ch.main) and ch.main[b["height"]] == h,
            "confirmations": ch.height - b["height"] + 1,
        }
        if full:
            out["next"] = ch.main[b["height"] + 1] if b["height"] < ch.height else None
            out["txs"] = [self.tx_view(t, h) for t in b["txs"]]
            out["effects"] = ch.effects.get(h, [])
        return out

    def tx_view(self, tx, block_hash=None):
        ch = self.chain
        v = {k: x for k, x in tx.items() if k not in ("sig", "work")}
        v["txid"] = C.txid(tx)
        if block_hash:
            v.update(block=block_hash, height=ch.blocks[block_hash]["height"],
                     time=ch.blocks[block_hash]["time"],
                     confirmations=ch.height - ch.blocks[block_hash]["height"] + 1)
        else:
            v.update(block=None, confirmations=0, time=ch.mempool_seen.get(v["txid"]))
        return v

    def job_view(self, jid):
        j = dict(self.chain.state.jobs[jid], id=jid)
        j.pop("gen", None)
        return j

    def loan_view(self, lid):
        return dict(self.chain.state.loans[lid], id=lid)

    def id_view(self, id_):
        ch = self.chain
        with ch.lock:
            st = ch.state
            acct = st.accounts.get(id_)
            pend = ch.pending.accounts.get(id_)
            hist = []
            for height, kind, ref, i in ch.id_index.get(id_, [])[-80:][::-1]:
                if kind == "tx":
                    hist.append(dict(self.tx_view(ch.blocks[ch.tx_index[ref][0]]["txs"][i],
                                                  ch.tx_index[ref][0]), kind="action"))
                else:
                    e = dict(ch.effects[ref][i], kind="money", height=height,
                             time=ch.blocks[ref]["time"], block=ref)
                    if e["why"] == "payment" and e["ref"] in ch.tx_index:
                        bh, k = ch.tx_index[e["ref"]]
                        e["memo"] = ch.blocks[bh]["txs"][k].get("memo", "")
                    hist.append(e)
            pending = [self.tx_view(t) for t in ch.mempool.values()
                       if t["from"] == id_ or t.get("to") == id_]
            jobs = [self.job_view(k) for k, j in st.jobs.items() if id_ in (j["poster"], j["worker"])]
            loans = [self.loan_view(k) for k, l in st.loans.items() if id_ in (l["borrower"], l["lender"])]
            return {
                "id": id_, "display": C.format_id(id_),
                "registered": acct is not None,
                "registering": acct is None and pend is not None,
                "pub": (acct or pend or {}).get("pub"),
                "balance": acct["bal"] if acct else 0,
                "available": pend["bal"] if pend else 0,
                "nonce": acct["nonce"] if acct else 0,
                "next_nonce": pend["nonce"] if pend else 0,
                "history": hist, "pending": pending,
                "jobs": sorted(jobs, key=lambda j: -j["posted"])[:50],
                "loans": sorted(loans, key=lambda l: -l["requested"])[:50],
                "owes": [l for l in loans if l["borrower"] == id_ and l["status"] in ("active", "overdue")],
            }


def make_handler(node):
    ch = node.chain

    class H(BaseHTTPRequestHandler):
        server_version = "cinderd/0.1"

        def log_message(self, *a):
            pass

        def _cors(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Access-Control-Allow-Private-Network", "true")

        def send_json(self, obj, code=200):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self._cors()
            self.end_headers()
            self.wfile.write(data)

        def err(self, msg, code=400):
            self.send_json({"error": msg}, code)

        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 4_000_000:
                raise ValueError("body too large")
            return json.loads(self.rfile.read(n) or b"{}")

        def do_OPTIONS(self):
            self.send_response(204)
            self._cors()
            self.end_headers()

        # -------------------------------------------------------- GET
        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            parts = [p for p in u.path.split("/") if p]
            try:
                if parts[:1] == ["api"]:
                    return self.api_get(parts[1:], q)
                if parts[:1] == ["p2p"]:
                    return self.p2p_get(parts[1:], q)
                return self.static(u.path)
            except Invalid as e:
                return self.err(str(e))
            except (KeyError, IndexError, ValueError):
                return self.err("not found", 404)

        def api_get(self, p, q):
            if p == ["status"]:
                return self.send_json(node.status())
            if p == ["params"]:
                return self.send_json({
                    "chain_id": C.CHAIN_ID, "ticker": C.TICKER, "ash_per_cdr": C.ASH_PER_CDR,
                    "block_time": C.TARGET_BLOCK_TIME, "blocks_per_day": C.BLOCKS_PER_DAY,
                    "initial_reward": C.INITIAL_REWARD, "cooling_period": C.COOLING_PERIOD,
                    "max_supply_cdr": C.MAX_SUPPLY_CDR, "min_fee": C.MIN_FEE,
                    "register_work_bits": C.REGISTER_WORK_BITS, "tx_work_bits": C.TX_WORK_BITS,
                    "review_blocks": C.REVIEW_BLOCKS, "max_rejects": C.MAX_REJECTS,
                    "auto_repay_percent": C.AUTO_REPAY_PERCENT,
                    "window": [C.MIN_WINDOW, C.MAX_WINDOW], "term": [C.MIN_TERM, C.MAX_TERM],
                    "genesis": ch.genesis_hash})
            if p == ["blocks"]:
                with ch.lock:
                    start = int(q.get("from", ch.height))
                    limit = min(int(q.get("limit", 20)), 100)
                    hs = [ch.main[i] for i in range(min(start, ch.height), -1, -1)][:limit]
                    return self.send_json([node.block_view(h, full=False) for h in hs])
            if len(p) == 2 and p[0] == "block":
                with ch.lock:
                    h = ch.main[int(p[1])] if p[1].isdigit() else p[1]
                    return self.send_json(node.block_view(h))
            if len(p) == 2 and p[0] == "tx":
                with ch.lock:
                    if p[1] in ch.mempool:
                        return self.send_json(node.tx_view(ch.mempool[p[1]]))
                    bh, i = ch.tx_index[p[1]]
                    v = node.tx_view(ch.blocks[bh]["txs"][i], bh)
                    ref = v.get("job") or v.get("loan") or v["txid"]
                    v["effects"] = [e for e in ch.effects.get(bh, []) if e["ref"] in (ref, v["txid"])]
                    return self.send_json(v)
            if len(p) == 2 and p[0] == "id":
                id_ = C.normalize_id(p[1])
                if not C.is_valid_id(id_):
                    return self.err("that isn't a valid Cinder ID; check for a typo")
                return self.send_json(node.id_view(id_))
            if len(p) == 2 and p[0] == "pub":
                with ch.lock:
                    return self.send_json({"id": ch.state.pubs.get(p[1]) or ch.pending.pubs.get(p[1])})
            if p in (["jobs"], ["loans"]):
                with ch.lock:
                    items = ch.state.jobs if p == ["jobs"] else ch.state.loans
                    view = node.job_view if p == ["jobs"] else node.loan_view
                    want = q.get("status")
                    keys = [k for k, v in items.items() if not want or v["status"] == want]
                    keys = keys[::-1][:min(int(q.get("limit", 50)), 200)]
                    return self.send_json([view(k) for k in keys])
            if len(p) == 2 and p[0] in ("job", "loan"):
                with ch.lock:
                    return self.send_json(node.job_view(p[1]) if p[0] == "job" else node.loan_view(p[1]))
            if p == ["mempool"]:
                with ch.lock:
                    return self.send_json([node.tx_view(t) for t in list(ch.mempool.values())[:200]])
            if p == ["mining", "template"]:
                id_ = C.normalize_id(q.get("id", ""))
                if not C.is_valid_id(id_):
                    return self.err("pass ?id=<your Cinder ID>")
                note = q.get("note", "")[:80].replace("|", "")
                b = ch.block_template(id_, note)
                return self.send_json({"block": b, "prefix": C.header_prefix(b), "target": b["target"]})
            return self.err("not found", 404)

        def p2p_get(self, p, q):
            if p == ["tip"]:
                with ch.lock:
                    return self.send_json({"hash": ch.tip, "height": ch.height,
                                           "cum": format(ch.meta[ch.tip]["cum"], "x"),
                                           "genesis": ch.genesis_hash})
            if p == ["hashes"]:
                with ch.lock:
                    lo, n = int(q["from"]), min(int(q.get("limit", 500)), 2000)
                    return self.send_json(ch.main[lo:lo + n])
            if p == ["blocks"]:
                with ch.lock:
                    lo, n = int(q["from"]), min(int(q.get("limit", 100)), 200)
                    return self.send_json([ch.blocks[h] for h in ch.main[lo:lo + n]])
            if len(p) == 2 and p[0] == "block":
                return self.send_json(ch.blocks[p[1]])
            if p == ["peers"]:
                return self.send_json(sorted(node.peers))
            return self.err("not found", 404)

        def static(self, path):
            rel = os.path.normpath(path).lstrip("/\\") or "index.html"
            full = os.path.join(SITE, rel)
            if os.path.isdir(full):
                full = os.path.join(full, "index.html")
            if not full.startswith(SITE) or not os.path.isfile(full):
                full = os.path.join(SITE, "index.html")
            with open(full, "rb") as f:
                data = f.read()
            ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
            if full.endswith(".js"):
                ctype = "text/javascript"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        # -------------------------------------------------------- POST
        def do_POST(self):
            parts = [p for p in urlparse(self.path).path.split("/") if p]
            try:
                body = self.body()
            except Exception:
                return self.err("bad JSON")
            try:
                if parts == ["api", "tx"]:
                    status, tid = ch.add_tx(body)
                    if status == "accepted":
                        node.announce_tx(body)
                    return self.send_json({"status": status, "txid": tid})
                if parts == ["api", "mining", "submit"]:
                    status, h = ch.add_block(body)
                    if status in ("new-tip", "side"):
                        node.announce_block(body)
                        print(f"[mine] block {body['height']} {h[:16]}... by {body['miner']}")
                    return self.send_json({"status": status, "hash": h})
                if parts == ["p2p", "block"]:
                    b, src = body["block"], body.get("from")
                    status, h = ch.add_block(b)
                    if status in ("new-tip", "side"):
                        node.announce_block(b, skip=src)
                    elif status == "orphan" and src:
                        threading.Thread(target=lambda: node.sync_with(src), daemon=True).start()
                    return self.send_json({"status": status})
                if parts == ["p2p", "tx"]:
                    status, tid = ch.add_tx(body["tx"])
                    if status == "accepted":
                        node.announce_tx(body["tx"], skip=body.get("from"))
                    return self.send_json({"status": status})
                if parts == ["p2p", "hello"]:
                    url = str(body.get("url", "")).rstrip("/")
                    if url.startswith(("http://", "https://")) and url != node.public_url \
                            and len(node.peers) < 64:
                        node.peers.add(url)
                    return self.send_json({"peers": sorted(node.peers),
                                           "genesis": ch.genesis_hash})
                return self.err("not found", 404)
            except Invalid as e:
                return self.err(str(e))
            except (KeyError, TypeError, ValueError) as e:
                return self.err(f"malformed request: {e}")

    return H


def main(argv=None):
    global SITE
    ap = argparse.ArgumentParser(description="Cinder full node")
    ap.add_argument("--port", type=int, default=8642)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--datadir", default=os.path.join(ROOT, "data"))
    ap.add_argument("--site", default=SITE, help="folder with the website to serve")
    ap.add_argument("--peer", action="append", default=[], help="peer URL (repeatable)")
    ap.add_argument("--public-url", help="URL other nodes can reach you at")
    a = ap.parse_args(argv)
    SITE = os.path.abspath(a.site)

    node = Node(a.datadir, a.peer, a.public_url)
    threading.Thread(target=node.peer_loop, daemon=True).start()
    srv = ThreadingHTTPServer((a.host, a.port), make_handler(node))
    print(f"[node] Cinder {C.CHAIN_ID} | genesis {node.chain.genesis_hash[:16]}...")
    print(f"[node] height {node.chain.height} | http://localhost:{a.port}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[node] bye")


if __name__ == "__main__":
    main()
