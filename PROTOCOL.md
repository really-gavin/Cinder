# Cinder protocol (testnet-2)

The source of truth is `cinder/consensus.py` (formats and limits) and
`cinder/state.py` (what each transaction does). This file explains them.

## Money
- 1 CDR = 100,000,000 ash. Everything on the wire is an integer in ash.
- New coins only come from mining. There is no sign-up bonus and no pre-mine.
- CDR is its own currency. The protocol has no dollar price and no way to
  cash out. If a separate company ever offers CDR-to-dollar exchange, that
  company runs it and carries the licensing.

## Cards
Every wallet is a card:

| Part | What it is | Who chooses |
|---|---|---|
| **Card ID** | 16 characters in fours, e.g. `K5V9 9MG8 G9BF GTC7`. Public: people pay you with it. | Made from your key |
| **PIN** | 6 digits. Unlocks the card on your device. | You |
| **SPP** (Secure Payment Pin) | 6 characters. Asked for on every payment, like a CVV. | Generated |
| **Backup words** | 12 words. Bring the card back on any device. | Generated |

- The ID alphabet is Crockford base32 (no I, L, O, U). The 16th character
  is a check digit (weighted sum mod 31) that catches typos. `O` is read as
  `0` and `I`/`L` as `1` when typed.
- ID = 15 characters from SHA256d("cinder-id|" + public key), plus the
  check digit. Because the ID comes from the key, nobody can claim yours.
- Backup words: 16 random bytes as 12 BIP-39 English words (standard
  checksum). Private key = SHA256("cinder-key|" + those bytes).
- On a device, the backup bytes are encrypted with AES-GCM under a key
  derived (PBKDF2-SHA256, 210,000 rounds) from PIN + SPP together. The PIN
  alone only opens the view. Forgot the PIN or SPP? Restore from the words
  and you get a new SPP.
- A card must be **registered** on the network once before it can receive.
  Registration is free; the device does a few seconds of work.

## Transactions
Every transaction has `type, from, nonce, fee, work, sig` plus its own fields.

- Message = `cinder-testnet-2|` + the transaction as JSON with sorted keys
  and no spaces, without `sig`. `sig` is a 64-byte compact low-S ECDSA
  (secp256k1) signature over SHA256(message). Receipt number = SHA256d(message).
- **Free or fee.** With `fee: 0`, the receipt number must start with 18 zero
  bits (20 for registering), about half a second of work on a phone. Or pay
  a fee of at least 1,000 ash and skip the work.
- `nonce` counts the card's transactions, so nothing can be replayed.

| Type | Fields | What it does |
|---|---|---|
| `register` | `pub` | Puts a new card on the network |
| `pay` | `to, amount, memo` | Sends CDR to a registered card. Memo up to 80 bytes |
| `job_post` | `title, pay, open_blocks, work_blocks` | Locks `pay` on the network and lists the job |
| `job_cancel` | `job` | Poster cancels a job nobody has taken; pay comes back |
| `job_take` | `job` | Worker takes an open job; the work clock starts |
| `job_submit` | `job, note` | Worker hands in; the poster's review day starts |
| `job_approve` | `job` | Poster pays the worker now |
| `job_reject` | `job, note` | Poster sends it back (max 2 times); work clock restarts |
| `loan_request` | `amount, repay, term, memo` | Asks for a loan; `repay` is the total to pay back |
| `loan_cancel` | `loan` | Borrower withdraws an unfunded request |
| `loan_fund` | `loan` | Lender sends `amount` to the borrower; the loan starts |
| `loan_repay` | `loan, amount` | Borrower pays some or all of what's owed |
| `loan_close` | `loan` | Borrower or lender closes it early (see below) |

## Jobs: the clock decides
- Unclaimed when `open_blocks` runs out: pay goes back to the poster.
- Taken but not handed in within `work_blocks`: pay goes back to the poster.
- Handed in: the poster has 1,440 blocks (about a day) to approve or send
  back. If they do neither, **the worker is paid automatically**.
- After 2 send-backs the poster can only approve or let the day run out.
- Windows can be 10 blocks to 90 days.

## Loans
- Interest is whatever the two sides agree: `repay` can be any amount at
  least equal to `amount`.
- **Due date** (`term` blocks after funding): the network takes what it can
  from the borrower's balance. Anything still owed makes the loan overdue.
- **Overdue**: 50% of every coin the borrower receives (payments, job pay,
  mining rewards, refunds) goes to their oldest overdue loan first, until
  it's paid off.
- **Closing early**: either side can close. Interest is dropped (only the
  amount borrowed, minus anything already repaid, is owed), the borrower's
  balance pays as much as it can right away, and the rest is collected like
  an overdue loan.
- Nobody guarantees repayment. A borrower who never receives anything again
  never pays.

## Blocks
- Header: `cinder-testnet-2|height|prev|time|target|miner|tx_root|note|nonce`.
  Hash = SHA256d(header); valid when hash <= target.
- `miner` is a card ID; it gets the block reward plus the block's fees. Its
  registration can be in the same block.
- Up to 1,000 transactions. Timestamps must beat the median of the last 11
  blocks and be no more than 2 hours ahead.
- After the transactions, the network runs anything due at this height:
  job clocks and loan due dates. These "automatic moves" are recorded per
  block and shown in the explorer.

## Difficulty and reward
- 60-second target, retargeted every block from the last 30 (clamped 4x).
- Cooling reward: 40 CDR at launch, sliding smoothly to half by the end of
  each ~year (525,600 blocks), then continuing from there. No halving
  cliffs. Total ever: 31,536,000 CDR.

## Fork choice
Most cumulative work wins. A reorg replays state from genesis along the
heavier branch; transactions from dropped blocks go back to the mempool.

## Peer protocol (HTTP + JSON)
`GET /p2p/tip`, `GET /p2p/hashes`, `GET /p2p/blocks`, `GET /p2p/block/<hash>`,
`POST /p2p/block`, `POST /p2p/tx`, `POST /p2p/hello`. Nodes sync every 10 s
and relay new blocks and transactions.

## Known testnet limits (fix before a public launch)
- Python CPU mining only.
- Reorgs replay from genesis: fine for thousands of blocks, slow for millions.
- No peer banning or rate limiting beyond per-card pending limits.
- No independent security review.
