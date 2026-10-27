"""Cinder consensus rules (testnet-2).

Everything every node must agree on lives here: parameters, hashing, IDs,
transaction formats, the block reward and difficulty. The state machine
(what each transaction does to balances, jobs and loans) is in state.py.
Change anything in either file and you have made a different coin.
"""
import hashlib
import json

from ecdsa import SECP256k1, VerifyingKey, BadSignatureError
from ecdsa.util import sigdecode_string

# ---------------------------------------------------------------- parameters
CHAIN_ID = "cinder-testnet-2"
TICKER = "CDR"
ASH_PER_CDR = 100_000_000             # smallest unit is one "ash"
TARGET_BLOCK_TIME = 60                # seconds
BLOCKS_PER_DAY = 86_400 // TARGET_BLOCK_TIME
INITIAL_REWARD = 40 * ASH_PER_CDR     # reward at block 1
COOLING_PERIOD = 525_600              # blocks per cooling epoch (~1 year)
RETARGET_WINDOW = 30
MEDIAN_WINDOW = 11
MAX_FUTURE_DRIFT = 2 * 60 * 60
MAX_TXS_PER_BLOCK = 1_000
MIN_FEE = 1_000                       # ash, for transactions that pay a fee
MAX_PENDING_PER_SENDER = 16
MAX_TARGET = (1 << 256) // (1 << 16)
GENESIS_TARGET = (1 << 256) // (1 << 20)
CURVE_N = SECP256k1.order

# Fee-free transactions carry a little proof of work instead of a fee.
REGISTER_WORK_BITS = 20               # making a new ID: a couple of seconds
TX_WORK_BITS = 18                     # any other free transaction: under a second

# Jobs
REVIEW_BLOCKS = BLOCKS_PER_DAY        # poster has a day to approve or reject
MAX_REJECTS = 2                       # after this, only approve or the clock
MIN_WINDOW, MAX_WINDOW = 10, 90 * BLOCKS_PER_DAY

# Loans
AUTO_REPAY_PERCENT = 50               # share of income that goes to overdue loans
MIN_TERM, MAX_TERM = 10, 2 * COOLING_PERIOD

GENESIS = {
    "height": 0,
    "prev": "00" * 32,
    "time": 1793001600,               # 26 Oct 2026 08:00 UTC
    "target": format(GENESIS_TARGET, "064x"),
    "miner": "",
    "nonce": 0,
    "note": "26 Oct 2026 - first light. Nothing printed, everything burned for.",
    "txs": [],
}


# ---------------------------------------------------------------- hashing
def sha256(b: bytes) -> bytes:
    return hashlib.sha256(b).digest()


def sha256d(b: bytes) -> bytes:
    return sha256(sha256(b))


def canonical_json(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


# ---------------------------------------------------------------- card IDs
# 16 characters, read in groups of 4 like a card number. Crockford base32:
# no I, L, O or U, so nothing looks like anything else. The last character
# is a check digit that catches typos.
ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
ID_LEN = 16


def id_check_char(body: str) -> str:
    total = sum((i + 1) * ID_ALPHABET.index(c) for i, c in enumerate(body))
    return ID_ALPHABET[total % 31]


def normalize_id(s: str) -> str:
    s = str(s).upper().replace(" ", "").replace("-", "")
    return s.replace("O", "0").replace("I", "1").replace("L", "1")


def is_valid_id(s) -> bool:
    if not isinstance(s, str) or len(s) != ID_LEN:
        return False
    if any(c not in ID_ALPHABET for c in s):
        return False
    return id_check_char(s[:-1]) == s[-1]


def format_id(s: str) -> str:
    return " ".join(s[i:i + 4] for i in range(0, len(s), 4))


# ---------------------------------------------------------------- transactions
# Every transaction: type, from (ID), nonce, fee, work, sig + its own fields.
COMMON = ("type", "from", "nonce", "fee", "work", "sig")
TX_TYPES = {
    "register":     {"pub": "pub"},
    "pay":          {"to": "id", "amount": "amount", "memo": "text80"},
    "job_post":     {"title": "text120", "pay": "amount", "open_blocks": "window", "work_blocks": "window"},
    "job_cancel":   {"job": "hash"},
    "job_take":     {"job": "hash"},
    "job_submit":   {"job": "hash", "note": "text200"},
    "job_approve":  {"job": "hash"},
    "job_reject":   {"job": "hash", "note": "text200"},
    "loan_request": {"amount": "amount", "repay": "amount", "term": "term", "memo": "text120"},
    "loan_cancel":  {"loan": "hash"},
    "loan_fund":    {"loan": "hash"},
    "loan_repay":   {"loan": "hash", "amount": "amount"},
    "loan_close":   {"loan": "hash"},
}


def tx_message(tx) -> bytes:
    body = {k: v for k, v in tx.items() if k != "sig"}
    return (CHAIN_ID + "|" + canonical_json(body)).encode()


def txid(tx) -> str:
    return sha256d(tx_message(tx)).hex()


def _is_int(v, lo, hi):
    return isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi


def _is_hex(s, nbytes):
    if not isinstance(s, str) or len(s) != nbytes * 2 or s != s.lower():
        return False
    try:
        bytes.fromhex(s)
        return True
    except ValueError:
        return False


def _is_text(v, nbytes):
    return isinstance(v, str) and len(v.encode()) <= nbytes and not any(ord(c) < 32 for c in v)


def _check_field(kind, v):
    if kind == "pub":
        return _is_hex(v, 33) and v[:2] in ("02", "03")
    if kind == "id":
        return is_valid_id(v)
    if kind == "amount":
        return _is_int(v, 1, 2**62)
    if kind == "hash":
        return _is_hex(v, 32)
    if kind == "window":
        return _is_int(v, MIN_WINDOW, MAX_WINDOW)
    if kind == "term":
        return _is_int(v, MIN_TERM, MAX_TERM)
    if kind.startswith("text"):
        return _is_text(v, int(kind[4:]))
    return False


def work_target(tx):
    bits = REGISTER_WORK_BITS if tx["type"] == "register" else TX_WORK_BITS
    return (1 << (256 - bits)) - 1


def check_tx_shape(tx):
    """Stateless checks. Raises ValueError with a reason people can read."""
    if not isinstance(tx, dict) or tx.get("type") not in TX_TYPES:
        raise ValueError("unknown transaction type")
    fields = TX_TYPES[tx["type"]]
    if set(tx) != set(COMMON) | set(fields):
        raise ValueError(f"{tx['type']} needs exactly: {sorted(set(COMMON) | set(fields))}")
    for k, kind in fields.items():
        if not _check_field(kind, tx[k]):
            raise ValueError(f"bad {k}")
    if not is_valid_id(tx["from"]):
        raise ValueError("bad sender ID")
    if not _is_int(tx["nonce"], 0, 2**62) or not _is_int(tx["work"], 0, 2**62):
        raise ValueError("bad nonce or work")
    if not _is_int(tx["fee"], 0, 2**62):
        raise ValueError("bad fee")
    if not _is_hex(tx["sig"], 64) or not (0 < int(tx["sig"][64:], 16) <= CURVE_N // 2):
        raise ValueError("signature must be 64-byte compact low-S")
    if tx["type"] == "register":
        if tx["fee"] != 0 or tx["nonce"] != 0:
            raise ValueError("register has no fee and nonce 0")
        if tx["from"] != id_from_pub(tx["pub"]):
            raise ValueError("that ID doesn't belong to that key")
    if tx["fee"] == 0:
        if int(txid(tx), 16) > work_target(tx):
            raise ValueError("free transaction is missing its proof of work")
    elif tx["fee"] < MIN_FEE:
        raise ValueError(f"fee must be 0 (with work) or at least {MIN_FEE} ash")
    if tx["type"] == "pay" and tx["to"] == tx["from"]:
        raise ValueError("cannot pay yourself")
    if tx["type"] == "loan_request" and tx["repay"] < tx["amount"]:
        raise ValueError("repay total can't be less than the amount borrowed")
    if tx["type"] == "register":
        verify_sig(tx["pub"], tx)


def verify_sig(pub_hex, tx):
    try:
        vk = VerifyingKey.from_string(bytes.fromhex(pub_hex), curve=SECP256k1)
        vk.verify_digest(bytes.fromhex(tx["sig"]), sha256(tx_message(tx)),
                         sigdecode=sigdecode_string)
    except (BadSignatureError, Exception):
        raise ValueError("signature does not verify")


# ---------------------------------------------------------------- blocks
def merkle_root(txids):
    if not txids:
        return "00" * 32
    level = [bytes.fromhex(t) for t in txids]
    while len(level) > 1:
        if len(level) % 2:
            level.append(level[-1])
        level = [sha256d(level[i] + level[i + 1]) for i in range(0, len(level), 2)]
    return level[0].hex()


def header_prefix(b) -> str:
    root = b.get("tx_root") or merkle_root([txid(t) for t in b["txs"]])
    return (f"{CHAIN_ID}|{b['height']}|{b['prev']}|{b['time']}|{b['target']}|"
            f"{b['miner']}|{root}|{b.get('note', '')}|")


def block_hash(b) -> str:
    return sha256d((header_prefix(b) + str(b["nonce"])).encode()).hex()


def block_work(target: int) -> int:
    return (1 << 256) // (target + 1)


# ---------------------------------------------------------------- reward
def block_reward(height: int) -> int:
    """The cooling curve: no halving cliffs. Within each ~year the reward
    slides linearly from `base` to `base/2`. Sums to 31,536,000 CDR."""
    if height <= 0:
        return 0
    epoch, step = divmod(height - 1, COOLING_PERIOD)
    base = INITIAL_REWARD >> epoch
    if base == 0:
        return 0
    return base - (base // 2) * step // COOLING_PERIOD


MAX_SUPPLY_CDR = 3 * INITIAL_REWARD * COOLING_PERIOD // 2 // ASH_PER_CDR


# ---------------------------------------------------------------- difficulty
def next_target(ancestors):
    window = ancestors[-RETARGET_WINDOW:]
    if len(window) < 2:
        return GENESIS_TARGET
    expected = TARGET_BLOCK_TIME * (len(window) - 1)
    actual = window[-1][0] - window[0][0]
    actual = max(expected // 4, min(actual, expected * 4))
    avg = sum(t for _, t in window) // len(window)
    return min(MAX_TARGET, max(1, avg * actual // expected))


# ---------------------------------------------------------------- backup phrase
def _words():
    import os
    with open(os.path.join(os.path.dirname(__file__), "words.txt")) as f:
        return f.read().split()


def phrase_from_entropy(entropy: bytes) -> str:
    """12 words from 16 bytes, BIP-39 style (last 4 bits are a checksum)."""
    words = _words()
    bits = int.from_bytes(entropy, "big") << 4 | sha256(entropy)[0] >> 4
    return " ".join(words[(bits >> (11 * (11 - i))) & 2047] for i in range(12))


def entropy_from_phrase(phrase: str) -> bytes:
    words = _words()
    parts = phrase.lower().split()
    if len(parts) != 12 or any(w not in words for w in parts):
        raise ValueError("a backup phrase is 12 words from the standard list")
    bits = 0
    for w in parts:
        bits = bits << 11 | words.index(w)
    entropy = (bits >> 4).to_bytes(16, "big")
    if sha256(entropy)[0] >> 4 != bits & 15:
        raise ValueError("those words don't check out; look for a typo")
    return entropy


def key_from_entropy(entropy: bytes) -> bytes:
    k = sha256(b"cinder-key|" + entropy)
    while not (0 < int.from_bytes(k, "big") < CURVE_N):
        k = sha256(k)
    return k


def id_from_pub(pub_hex: str) -> str:
    """Your ID comes from your public key, so nobody else can claim it, and
    restoring your backup phrase brings back the same card number."""
    h = int.from_bytes(sha256d(b"cinder-id|" + bytes.fromhex(pub_hex)), "big")
    body = "".join(ID_ALPHABET[(h >> (5 * i)) & 31] for i in range(ID_LEN - 1))
    return body + id_check_char(body)
