"""What every transaction does: balances, jobs and loans.

Each handler checks everything first and only then changes state, so a
transaction that fails leaves the state untouched.
"""
import copy

from . import consensus as C


class Invalid(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise Invalid(msg)


class State:
    def __init__(self):
        self.accounts = {}   # id -> {"pub", "bal", "nonce"}
        self.pubs = {}       # pub -> id
        self.jobs = {}       # job id (txid of the post) -> job
        self.loans = {}      # loan id (txid of the request) -> loan
        self.debts = {}      # borrower id -> [loan ids that are active/overdue]
        self.due = {}        # height -> [event]
        self.supply = 0
        self.log = []        # transfers made by the block being applied

    def copy(self):
        log, self.log = self.log, []
        st = copy.deepcopy(self)
        self.log = log
        return st

    # ------------------------------------------------------------ money
    def acct(self, id_):
        a = self.accounts.get(id_)
        need(a is not None, f"{C.format_id(id_)} isn't a registered Cinder ID")
        return a

    def debit(self, id_, amount, why, ref=None, to=None):
        a = self.acct(id_)
        need(a["bal"] >= amount, "not enough CDR")
        a["bal"] -= amount
        if to is None and amount:
            self.log.append({"from": id_, "to": None, "amount": amount, "why": why, "ref": ref})

    def credit(self, id_, amount, why, ref=None, frm=None, height=0):
        """Every coin that arrives goes through here, so overdue loans get
        their share of income automatically."""
        if amount <= 0:
            return
        a = self.acct(id_)
        a["bal"] += amount
        self.log.append({"from": frm, "to": id_, "amount": amount, "why": why, "ref": ref})
        pool = amount * C.AUTO_REPAY_PERCENT // 100
        for lid in list(self.debts.get(id_, [])):
            if pool <= 0:
                break
            loan = self.loans[lid]
            if loan["status"] != "overdue":
                continue
            take = min(pool, loan["owed"], a["bal"])
            if take <= 0:
                continue
            pool -= take
            a["bal"] -= take
            self._repaid(lid, take, "auto-repay", height)

    def _repaid(self, lid, amount, why, height):
        loan = self.loans[lid]
        loan["owed"] -= amount
        loan["paid"] += amount
        if loan["owed"] == 0:
            loan["status"] = "paid"
            self.debts[loan["borrower"]].remove(lid)
        self.credit(loan["lender"], amount, why, lid, loan["borrower"], height)

    def schedule(self, height, event):
        self.due.setdefault(height, []).append(event)

    # ------------------------------------------------------------ transactions
    def apply_tx(self, tx, height):
        t = tx["type"]
        tid = C.txid(tx)
        if t == "register":
            need(tx["from"] not in self.accounts, "that ID is already registered")
            need(tx["pub"] not in self.pubs, "that key already has an ID")
            self.accounts[tx["from"]] = {"pub": tx["pub"], "bal": 0, "nonce": 1}
            self.pubs[tx["pub"]] = tx["from"]
            return
        a = self.acct(tx["from"])
        try:
            C.verify_sig(a["pub"], tx)
        except ValueError as e:
            raise Invalid(str(e))
        need(tx["nonce"] == a["nonce"], f"nonce must be {a['nonce']}")
        # A locked card can only move its money out (pay) or be unlocked.
        need(not a.get("locked") or t in ("pay", "unlock"), "this card is locked. Unlock it, or move the money to another card")
        getattr(self, "_" + t)(tx, tid, height, a)   # checks, then changes
        a["nonce"] += 1
        if tx["fee"]:
            self.log.append({"from": tx["from"], "to": None, "amount": tx["fee"], "why": "fee", "ref": tid})

    def _fee_ok(self, a, tx, spend=0):
        need(a["bal"] >= spend + tx["fee"], "not enough CDR (including the fee)")

    def _pay_fee(self, a, tx):
        a["bal"] -= tx["fee"]

    # lock: the owner freezes the card, e.g. after someone kept guessing its SPP
    def _lock(self, tx, tid, h, a):
        need(not a.get("locked"), "this card is already locked")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        a["locked"] = True

    def _unlock(self, tx, tid, h, a):
        need(a.get("locked"), "this card isn't locked")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        a["locked"] = False

    # pay
    def _pay(self, tx, tid, h, a):
        self.acct(tx["to"])
        self._fee_ok(a, tx, tx["amount"])
        self._pay_fee(a, tx)
        a["bal"] -= tx["amount"]
        self.credit(tx["to"], tx["amount"], "payment", tid, tx["from"], h)

    # jobs ---------------------------------------------------------
    def _job(self, jid):
        j = self.jobs.get(jid)
        need(j is not None, "no such job")
        return j

    def _job_post(self, tx, tid, h, a):
        self._fee_ok(a, tx, tx["pay"])
        self._pay_fee(a, tx)
        self.debit(tx["from"], tx["pay"], "job escrow", tid)
        self.jobs[tid] = {
            "poster": tx["from"], "title": tx["title"], "pay": tx["pay"],
            "status": "open", "worker": None, "posted": h,
            "open_until": h + tx["open_blocks"], "work_blocks": tx["work_blocks"],
            "deadline": None, "review_until": None, "rejects": 0, "note": "", "gen": 0,
        }
        self.schedule(h + tx["open_blocks"], ["job_open", tid, 0])

    def _job_cancel(self, tx, tid, h, a):
        j = self._job(tx["job"])
        need(j["poster"] == tx["from"], "only the poster can cancel a job")
        need(j["status"] == "open", "only an open job that nobody has taken can be cancelled")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        j["status"] = "cancelled"
        j["gen"] += 1
        self.credit(j["poster"], j["pay"], "job refund", tx["job"], None, h)

    def _job_take(self, tx, tid, h, a):
        j = self._job(tx["job"])
        need(j["status"] == "open", "that job isn't open")
        need(j["poster"] != tx["from"], "you can't take your own job")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        j.update(status="taken", worker=tx["from"], deadline=h + j["work_blocks"])
        j["gen"] += 1
        self.schedule(j["deadline"], ["job_work", tx["job"], j["gen"]])

    def _job_submit(self, tx, tid, h, a):
        j = self._job(tx["job"])
        need(j["worker"] == tx["from"], "only the worker can hand in this job")
        need(j["status"] == "taken", "this job isn't waiting for work")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        j.update(status="submitted", note=tx["note"], review_until=h + C.REVIEW_BLOCKS)
        j["gen"] += 1
        self.schedule(j["review_until"], ["job_review", tx["job"], j["gen"]])

    def _job_approve(self, tx, tid, h, a):
        j = self._job(tx["job"])
        need(j["poster"] == tx["from"], "only the poster can approve")
        need(j["status"] in ("taken", "submitted"), "there's nothing to approve")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        j["status"] = "paid"
        j["gen"] += 1
        self.credit(j["worker"], j["pay"], "job pay", tx["job"], j["poster"], h)

    def _job_reject(self, tx, tid, h, a):
        j = self._job(tx["job"])
        need(j["poster"] == tx["from"], "only the poster can send work back")
        need(j["status"] == "submitted", "there's no handed-in work to send back")
        need(j["rejects"] < C.MAX_REJECTS,
             f"work has been sent back {C.MAX_REJECTS} times; now it's approve or let the clock pay")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        j.update(status="taken", note=tx["note"], deadline=h + j["work_blocks"], review_until=None)
        j["rejects"] += 1
        j["gen"] += 1
        self.schedule(j["deadline"], ["job_work", tx["job"], j["gen"]])

    # loans --------------------------------------------------------
    def _loan(self, lid):
        l = self.loans.get(lid)
        need(l is not None, "no such loan")
        return l

    def _loan_request(self, tx, tid, h, a):
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        self.loans[tid] = {
            "borrower": tx["from"], "lender": None, "amount": tx["amount"],
            "repay": tx["repay"], "term": tx["term"], "memo": tx["memo"],
            "status": "open", "requested": h, "funded": None, "due": None,
            "owed": 0, "paid": 0,
        }

    def _loan_cancel(self, tx, tid, h, a):
        l = self._loan(tx["loan"])
        need(l["borrower"] == tx["from"], "only the borrower can withdraw a request")
        need(l["status"] == "open", "only an unfunded request can be withdrawn")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        l["status"] = "cancelled"

    def _loan_fund(self, tx, tid, h, a):
        l = self._loan(tx["loan"])
        need(l["status"] == "open", "that request isn't open")
        need(l["borrower"] != tx["from"], "you can't fund your own loan")
        self._fee_ok(a, tx, l["amount"])
        self._pay_fee(a, tx)
        a["bal"] -= l["amount"]
        l.update(status="active", lender=tx["from"], funded=h, due=h + l["term"], owed=l["repay"])
        self.debts.setdefault(l["borrower"], []).append(tx["loan"])
        self.schedule(l["due"], ["loan_due", tx["loan"], 0])
        self.credit(l["borrower"], l["amount"], "loan", tx["loan"], tx["from"], h)

    def _loan_repay(self, tx, tid, h, a):
        l = self._loan(tx["loan"])
        need(l["borrower"] == tx["from"], "only the borrower repays")
        need(l["status"] in ("active", "overdue"), "this loan isn't open for repayment")
        need(tx["amount"] <= l["owed"], f"only {l['owed']} ash is owed")
        self._fee_ok(a, tx, tx["amount"])
        self._pay_fee(a, tx)
        a["bal"] -= tx["amount"]
        self._repaid(tx["loan"], tx["amount"], "loan repay", h)

    def _loan_close(self, tx, tid, h, a):
        """Borrower shutting down, or lender calling it back. Whatever the
        borrower holds goes back right away. Closing early waives interest;
        anything still owed is collected from future income."""
        l = self._loan(tx["loan"])
        need(tx["from"] in (l["borrower"], l["lender"]), "only the borrower or lender can close a loan")
        need(l["status"] in ("active", "overdue"), "this loan is already settled")
        self._fee_ok(a, tx)
        self._pay_fee(a, tx)
        if h < l["due"]:
            l["owed"] = max(0, l["amount"] - l["paid"])
        l["due"] = h
        l["closed_by"] = tx["from"]
        self._sweep(tx["loan"], h)

    def _sweep(self, lid, h):
        l = self.loans[lid]
        b = self.accounts[l["borrower"]]
        take = min(b["bal"], l["owed"])
        if l["owed"] == 0:
            l["status"] = "paid"
            self.debts[l["borrower"]].remove(lid)
            return
        l["status"] = "overdue"
        if take:
            b["bal"] -= take
            self._repaid(lid, take, "loan due", h)

    # ------------------------------------------------------------ blocks
    def apply_block(self, block):
        h = block["height"]
        self.log = []
        fees = 0
        for tx in block["txs"]:
            self.apply_tx(tx, h)
            fees += tx["fee"]
        reward = C.block_reward(h)
        self.supply += reward
        if h > 0:
            self.acct(block["miner"])
            self.credit(block["miner"], reward + fees, "mined", None, None, h)
        for ev in self.due.pop(h, []):
            self._event(ev, h)

    def _event(self, ev, h):
        kind, ref, gen = ev
        if kind == "loan_due":
            l = self.loans[ref]
            if l["status"] == "active":
                self._sweep(ref, h)
            return
        j = self.jobs[ref]
        if j["gen"] != gen:
            return  # the job moved on since this was scheduled
        if kind == "job_open" and j["status"] == "open":
            j["status"] = "expired"
            j["gen"] += 1
            self.credit(j["poster"], j["pay"], "job refund", ref, None, h)
        elif kind == "job_work" and j["status"] == "taken":
            j["status"] = "missed"
            j["gen"] += 1
            self.credit(j["poster"], j["pay"], "job refund", ref, None, h)
        elif kind == "job_review" and j["status"] == "submitted":
            j["status"] = "paid"
            j["gen"] += 1
            self.credit(j["worker"], j["pay"], "job pay", ref, j["poster"], h)
