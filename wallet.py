#!/usr/bin/env python3
"""Cinder command-line wallet.

    python wallet.py new                       # new card: ID + 12-word backup
    python wallet.py restore                   # bring a card back from its 12 words
    python wallet.py id
    python wallet.py balance
    python wallet.py send "CN4K 7Q9X MA2B TW8P" 2.5 --memo "for the pizza"

Payments are free: your computer does a second of work instead of paying a
fee. Add --fee 0.0001 to pay a fee and skip the work.

The key lives in wallet.json next to this file (or --file). Whoever has
that file, or your 12 words, has your coins.
"""
import argparse
import hashlib
import json
import os
import secrets
import sys
import urllib.error
import urllib.request
from decimal import Decimal

from ecdsa import SECP256k1, SigningKey
from ecdsa.util import sigencode_string_canonize

from cinder import consensus as C


# ---------------------------------------------------------------- library
def call(node, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(node.rstrip("/") + path, data=data,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def keys_from_entropy(entropy: bytes):
    sk = SigningKey.from_string(C.key_from_entropy(entropy), curve=SECP256k1)
    pub = sk.get_verifying_key().to_string("compressed").hex()
    return sk, pub, C.id_from_pub(pub)


def build_tx(sk, fields, nonce, fee=0):
    """Fill in the common fields, do the work if it's free, and sign."""
    tx = dict(fields, nonce=nonce, fee=fee, work=0, sig="")
    if fee == 0:
        target = C.work_target(tx)
        marker = 918273645546372819
        tx["work"] = marker
        pre, post = C.tx_message(tx).split(str(marker).encode())
        mid = hashlib.sha256(pre)
        w = secrets.randbelow(2**40)
        while True:
            h = mid.copy()
            h.update(str(w).encode() + post)
            if int.from_bytes(hashlib.sha256(h.digest()).digest(), "big") <= target:
                break
            w += 1
        tx["work"] = w
        assert int(C.txid(tx), 16) <= target
    digest = C.sha256(C.tx_message(tx))
    tx["sig"] = sk.sign_digest_deterministic(digest, sigencode=sigencode_string_canonize).hex()
    return tx


def to_ash(cndr: str) -> int:
    v = Decimal(cndr) * C.ASH_PER_CDR
    if v != v.to_integral_value() or v <= 0:
        raise SystemExit("amounts are positive, with at most 8 decimal places")
    return int(v)


def fmt(ash: int) -> str:
    return f"{Decimal(ash) / C.ASH_PER_CDR:,.8f}".rstrip("0").rstrip(".") + " CDR"


# ---------------------------------------------------------------- CLI
def load(path):
    if not os.path.exists(path):
        raise SystemExit(f"no wallet at {path}. run: python wallet.py new")
    with open(path) as f:
        w = json.load(f)
    return keys_from_entropy(bytes.fromhex(w["entropy"]))


def save_and_register(path, entropy, node):
    sk, pub, id_ = keys_from_entropy(entropy)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"id": id_, "entropy": entropy.hex()}, f, indent=2)
    info = call(node, f"/api/id/{id_}")
    if info.get("registered"):
        return id_, "already on the network"
    print("registering your ID on the network (a few seconds of work)...", file=sys.stderr)
    res = call(node, "/api/tx", build_tx(sk, {"type": "register", "from": id_, "pub": pub}, 0))
    if "error" in res:
        return id_, f"saved, but the node refused registration: {res['error']}"
    return id_, "registered; it's usable once the next block is found"


def main():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--file", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "wallet.json"))
    common.add_argument("--node", default="http://localhost:8642")
    ap = argparse.ArgumentParser(description="Cinder wallet")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("new", "restore", "id", "balance"):
        sub.add_parser(name, parents=[common])
    s = sub.add_parser("send", parents=[common])
    s.add_argument("to")
    s.add_argument("amount")
    s.add_argument("--fee", default=None, help="pay a fee (CDR) instead of doing the work")
    s.add_argument("--memo", default="")
    a = ap.parse_args()

    if a.cmd in ("new", "restore"):
        if os.path.exists(a.file):
            raise SystemExit(f"{a.file} already exists; refusing to overwrite it")
        if a.cmd == "new":
            entropy = secrets.token_bytes(16)
        else:
            try:
                entropy = C.entropy_from_phrase(input("your 12 words: "))
            except ValueError as e:
                raise SystemExit(str(e))
        id_, msg = save_and_register(a.file, entropy, a.node)
        print(f"\n  Your ID   {C.format_id(id_)}")
        if a.cmd == "new":
            print(f"  Backup    {C.phrase_from_entropy(entropy)}\n")
            print("  Write those 12 words down. They're the only way back if you lose this file.")
        print(f"  ({msg})")
        return

    sk, pub, id_ = load(a.file)
    if a.cmd == "id":
        print(C.format_id(id_))
    elif a.cmd == "balance":
        info = call(a.node, f"/api/id/{id_}")
        print(f"{C.format_id(id_)}\n  balance  {fmt(info.get('balance', 0))}")
        for l in info.get("owes", []):
            print(f"  owes     {fmt(l['owed'])} on loan {l['id'][:12]} ({l['status']})")
    elif a.cmd == "send":
        to = C.normalize_id(a.to)
        if not C.is_valid_id(to):
            raise SystemExit("that isn't a valid Cinder ID (check for a typo)")
        info = call(a.node, f"/api/id/{id_}")
        if not info.get("registered"):
            raise SystemExit("your ID isn't on the network yet; wait for a block")
        fee = to_ash(a.fee) if a.fee else 0
        tx = build_tx(sk, {"type": "pay", "from": id_, "to": to,
                           "amount": to_ash(a.amount), "memo": a.memo},
                      info["next_nonce"], fee)
        res = call(a.node, "/api/tx", tx)
        if "error" in res:
            raise SystemExit(f"not sent: {res['error']}")
        print(f"sent. receipt {res['txid']}")


if __name__ == "__main__":
    main()
