#!/usr/bin/env python3
"""Cinder CPU miner ("stoker").

    python miner.py --id "CN4K 7Q9X MA2B TW8P" [--node http://localhost:8642] [--threads 4]

Asks the node for a block template, burns through nonces on every core,
and hands the block back the moment one clears the target.
"""
import argparse
import hashlib
import json
import multiprocessing as mp
import os
import signal
import time
import urllib.error
import urllib.request

CHUNK = 200_000


def call(node, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(node + path, data=data,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def worker(index, jobs, results, counter):
    """Each worker owns a slice of the nonce space and checks for new work
    between chunks."""
    sha = hashlib.sha256
    parent = os.getppid()
    job = jobs.get()
    while job is not None:
        job_id, prefix, target = job
        base = sha(prefix.encode())
        start = index << 40
        solved = False
        while not solved:
            for nonce in range(start, start + CHUNK):
                h = base.copy()
                h.update(str(nonce).encode())
                if int.from_bytes(sha(h.digest()).digest(), "big") <= target:
                    results.put((job_id, nonce))
                    solved = True
                    break
            with counter.get_lock():
                counter.value += nonce - start + 1
            start += CHUNK
            if not jobs.empty() or os.getppid() != parent:
                break
        if os.getppid() != parent:
            return  # the miner was stopped; don't keep burning CPU
        job = jobs.get()          # block until the next template (or None)
        while not jobs.empty():   # skip straight to the newest one
            job = jobs.get()


def fmt_rate(r):
    for unit in ("H/s", "kH/s", "MH/s", "GH/s"):
        if r < 1000:
            return f"{r:6.1f} {unit}"
        r /= 1000
    return f"{r:.1f} TH/s"


def _stop(*_):
    raise KeyboardInterrupt


def main():
    signal.signal(signal.SIGTERM, _stop)
    ap = argparse.ArgumentParser(description="Cinder CPU miner")
    ap.add_argument("--id", required=True, help="your Cinder ID; block rewards go here")
    ap.add_argument("--node", default="http://localhost:8642")
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 1)
    ap.add_argument("--note", default="", help="text stamped into blocks you find")
    a = ap.parse_args()
    node = a.node.rstrip("/")

    results = mp.Queue()
    queues = [mp.Queue() for _ in range(a.threads)]
    counter = mp.Value("Q", 0)
    procs = [mp.Process(target=worker, args=(i, queues[i], results, counter), daemon=True)
             for i in range(a.threads)]
    for p in procs:
        p.start()

    print(f"mining with {a.threads} thread(s), rewards to {a.id}")
    found = 0
    t0 = time.time()
    job_id = 0
    try:
        while True:
            try:
                q = "/api/mining/template?id=" + a.id.replace(" ", "")
                if a.note:
                    q += "&note=" + urllib.request.quote(a.note)
                tpl = call(node, q)
            except Exception as e:
                print(f"node unreachable ({e}); retrying in 5s")
                time.sleep(5)
                continue
            if "error" in tpl:
                raise SystemExit(tpl["error"])
            block, prefix, target = tpl["block"], tpl["prefix"], int(tpl["target"], 16)
            job_id += 1
            for q in queues:
                q.put((job_id, prefix, target))
            deadline = time.time() + 10
            solved = None
            while time.time() < deadline and solved is None:
                try:
                    jid, nonce = results.get(timeout=0.5)
                    if jid == job_id:
                        solved = nonce
                except Exception:
                    pass
            if solved is not None:
                block["nonce"] = solved
                res = call(node, "/api/mining/submit", block)
                if res.get("status") == "new-tip":
                    found += 1
                    print(f"  * block {block['height']:>6}  {res['hash'][:20]}...  "
                          f"txs {len(block['txs']):>3}  (found {found})")
                else:
                    print(f"  - block {block['height']} not accepted: {res}")
            el = time.time() - t0
            print(f"  {fmt_rate(counter.value / el if el else 0)}   height {block['height'] - 1}"
                  f"   blocks found {found}", end="\r", flush=True)
    except KeyboardInterrupt:
        print(f"\nstopped. blocks found: {found}")
    finally:
        for q in queues:
            q.put(None)


if __name__ == "__main__":
    main()
