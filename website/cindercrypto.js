// Cinder protocol helpers for the browser: hashing, IDs, backup words,
// transaction building and signing. Mirrors cinder/consensus.py exactly.
import * as secp from './vendor/secp256k1.js';
import WORDS from './vendor/words.js';

export const CHAIN_ID = 'cinder-testnet-3';
export const ASH = 100_000_000;
export const ID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const REGISTER_WORK_BITS = 20, TX_WORK_BITS = 18;
const enc = new TextEncoder();
export const hex = b => secp.etc.bytesToHex(b);
export const unhex = h => secp.etc.hexToBytes(h);
const concat = (...a) => { const o = new Uint8Array(a.reduce((n, x) => n + x.length, 0)); let i = 0; for (const x of a) { o.set(x, i); i += x.length; } return o; };

// ------------------------------------------------------------------ SHA-256
// Synchronous, with a reusable midstate so proof of work stays fast.
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);
const IV = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
const W = new Uint32Array(64);
function compress(H, b, off) {
  for (let i = 0, j = off; i < 16; i++, j += 4) W[i] = (b[j] << 24 | b[j + 1] << 16 | b[j + 2] << 8 | b[j + 3]) >>> 0;
  for (let i = 16; i < 64; i++) {
    const x = W[i - 15], y = W[i - 2];
    const s0 = (x >>> 7 | x << 25) ^ (x >>> 18 | x << 14) ^ (x >>> 3);
    const s1 = (y >>> 17 | y << 15) ^ (y >>> 19 | y << 13) ^ (y >>> 10);
    W[i] = (W[i - 16] + s0 + W[i - 7] + s1) >>> 0;
  }
  let a = H[0], bb = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
  for (let i = 0; i < 64; i++) {
    const S1 = (e >>> 6 | e << 26) ^ (e >>> 11 | e << 21) ^ (e >>> 25 | e << 7);
    const t1 = (h + S1 + ((e & f) ^ (~e & g)) + K[i] + W[i]) >>> 0;
    const S0 = (a >>> 2 | a << 30) ^ (a >>> 13 | a << 19) ^ (a >>> 22 | a << 10);
    const t2 = (S0 + ((a & bb) ^ (a & c) ^ (bb & c))) >>> 0;
    h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = bb; bb = a; a = (t1 + t2) >>> 0;
  }
  H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + bb) >>> 0; H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
  H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0; H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h) >>> 0;
}
function finish(H, tail, total) {
  const padLen = ((tail.length + 9 + 63) >> 6) << 6;
  const p = new Uint8Array(padLen); p.set(tail); p[tail.length] = 0x80;
  const bits = total * 8;
  for (let i = 0; i < 8; i++) p[padLen - 1 - i] = Math.floor(bits / 2 ** (8 * i)) & 255;
  for (let o = 0; o < padLen; o += 64) compress(H, p, o);
  const out = new Uint8Array(32);
  for (let i = 0; i < 8; i++) { out[4 * i] = H[i] >>> 24; out[4 * i + 1] = H[i] >>> 16; out[4 * i + 2] = H[i] >>> 8; out[4 * i + 3] = H[i]; }
  return out;
}
export function sha256(b) {
  const H = IV.slice(); const full = b.length & ~63;
  for (let o = 0; o < full; o += 64) compress(H, b, o);
  return finish(H, b.subarray(full), b.length);
}
export const sha256d = b => sha256(sha256(b));
function midstate(prefix) {
  const H = IV.slice(); const full = prefix.length & ~63;
  for (let o = 0; o < full; o += 64) compress(H, prefix, o);
  return { H, rest: prefix.slice(full), len: prefix.length };
}
function fromMid(m, more) {
  const H = m.H.slice(); const tail = concat(m.rest, more);
  const full = tail.length & ~63;
  for (let o = 0; o < full; o += 64) compress(H, tail, o);
  return finish(H, tail.subarray(full), m.len + more.length);
}

// Allocation-free search loop for the proof of work on free transactions.
const H1 = new Uint32Array(8), H2 = new Uint32Array(8), B2 = new Uint8Array(64);
function compressWords(H, b, off) { compress(H, b, off); }
function makeSearcher(preBytes, postBytes) {
  const mid = midstate(preBytes);
  const maxDigits = 16;
  const tailMax = mid.rest.length + maxDigits + postBytes.length + 9;
  const buf = new Uint8Array(((tailMax + 63) >> 6) << 6);
  buf.set(mid.rest);
  const digitAt = mid.rest.length;
  return function attempt(w) {           // returns first 32 bits of SHA256d
    const ds = String(w);
    for (let i = 0; i < ds.length; i++) buf[digitAt + i] = ds.charCodeAt(i);
    let n = digitAt + ds.length;
    buf.set(postBytes, n); n += postBytes.length;
    const total = mid.len + ds.length + postBytes.length;
    const padLen = ((n + 9 + 63) >> 6) << 6;
    buf[n] = 0x80; buf.fill(0, n + 1, padLen);
    const bits = total * 8;
    for (let i = 0; i < 6; i++) buf[padLen - 1 - i] = Math.floor(bits / 2 ** (8 * i)) & 255;
    for (let i = 0; i < 8; i++) H1[i] = mid.H[i];
    for (let o = 0; o < padLen; o += 64) compressWords(H1, buf, o);
    for (let i = 0; i < 8; i++) { B2[4 * i] = H1[i] >>> 24; B2[4 * i + 1] = H1[i] >>> 16; B2[4 * i + 2] = H1[i] >>> 8; B2[4 * i + 3] = H1[i]; }
    B2[32] = 0x80; B2.fill(0, 33, 62); B2[62] = 1; B2[63] = 0;   // 256-bit length
    for (let i = 0; i < 8; i++) H2[i] = IV[i];
    compressWords(H2, B2, 0);
    return H2[0];
  };
}

// ------------------------------------------------------------------ IDs
const checkChar = body => ID_ALPHABET[[...body].reduce((t, c, i) => t + (i + 1) * ID_ALPHABET.indexOf(c), 0) % 31];
export const normalizeId = s => String(s).toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
export const isValidId = s => typeof s === 'string' && s.length === 16 && [...s].every(c => ID_ALPHABET.includes(c)) && checkChar(s.slice(0, 15)) === s[15];
export const formatId = s => s ? s.match(/.{1,4}/g).join(' ') : '';
export function idFromPub(pubHex) {
  let h = BigInt('0x' + hex(sha256d(concat(enc.encode('cinder-id|'), unhex(pubHex)))));
  let body = '';
  for (let i = 0; i < 15; i++) body += ID_ALPHABET[Number((h >> BigInt(5 * i)) & 31n)];
  return body + checkChar(body);
}

// ------------------------------------------------------------------ backup words
export function phraseFromEntropy(entropy) {
  let bits = BigInt('0x' + hex(entropy)) << 4n | BigInt(sha256(entropy)[0] >> 4);
  const out = [];
  for (let i = 0; i < 12; i++) out.push(WORDS[Number((bits >> BigInt(11 * (11 - i))) & 2047n)]);
  return out;
}
export function entropyFromPhrase(phrase) {
  const parts = phrase.toLowerCase().trim().split(/\s+/);
  if (parts.length !== 12) throw new Error('A backup phrase is exactly 12 words.');
  let bits = 0n;
  for (const w of parts) {
    const i = WORDS.indexOf(w);
    if (i < 0) throw new Error(`"${w}" isn't one of the backup words. Check the spelling.`);
    bits = bits << 11n | BigInt(i);
  }
  const entropy = unhex((bits >> 4n).toString(16).padStart(32, '0'));
  if (BigInt(sha256(entropy)[0] >> 4) !== (bits & 15n)) throw new Error("Those words don't check out. One of them is probably wrong or out of order.");
  return entropy;
}
export function keysFromEntropy(entropy) {
  let k = sha256(concat(enc.encode('cinder-key|'), entropy));
  const n = secp.CURVE.n;
  while (true) { const v = BigInt('0x' + hex(k)); if (v > 0n && v < n) break; k = sha256(k); }
  const priv = hex(k), pub = hex(secp.getPublicKey(priv, true));
  return { priv, pub, id: idFromPub(pub) };
}
export const newEntropy = () => crypto.getRandomValues(new Uint8Array(16));
/** The SPP is 3 digits, worked out from the card's secret (the same one the 12 words rebuild).
 *  So restoring a card from its words brings the same SPP back; nothing extra to remember. */
export function sppFromEntropy(entropy) {
  const d = sha256(concat(enc.encode('cinder-spp|'), entropy));
  const n = ((d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3]) >>> 0;
  return String(n % 1000).padStart(3, '0');
}

// ------------------------------------------------------------------ transactions
function canonical(v) {
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (v && typeof v === 'object') return '{' + Object.keys(v).sort().map(k => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  return JSON.stringify(v);
}
export function txMessage(tx) {
  const body = { ...tx }; delete body.sig;
  return enc.encode(CHAIN_ID + '|' + canonical(body));
}
export const txid = tx => hex(sha256d(txMessage(tx)));

// Does the proof of work for a free transaction in small slices so the page
// stays responsive. onProgress gets a rough 0..1.
export async function buildTx(priv, fields, nonce, fee = 0, onProgress) {
  const tx = { ...fields, nonce, fee, work: 0, sig: '' };
  if (fee === 0) {
    const bits = fields.type === 'register' ? REGISTER_WORK_BITS : TX_WORK_BITS;
    const body = { ...tx, work: '__W__' }; delete body.sig;
    const [pre, post] = (CHAIN_ID + '|' + canonical(body)).split('"__W__"');
    const attempt = makeSearcher(enc.encode(pre), enc.encode(post));
    const expected = 2 ** bits, shift = 32 - bits;
    let w = Math.floor(Math.random() * 2 ** 40), tries = 0, found = false;
    while (!found) {
      for (let i = 0; i < 50000; i++, w++, tries++) {
        if ((attempt(w) >>> shift) === 0) { tx.work = w; found = true; break; }
      }
      if (!found) { onProgress?.(Math.min(0.95, tries / (expected * 1.5))); await new Promise(r => setTimeout(r, 0)); }
    }
    if (!txid(tx).startsWith('0'.repeat(bits >> 2))) throw new Error('proof of work self-check failed');
  }
  const body = { ...tx }; delete body.sig;
  const sig = await secp.signAsync(sha256(txMessage(body)), priv);
  tx.sig = hex(sig.toCompactRawBytes());
  return tx;
}

// ------------------------------------------------------------------ amounts
export function fmtCndr(ash, maxDp = 8) {
  const neg = ash < 0; ash = Math.abs(Math.round(ash));
  const whole = Math.floor(ash / ASH), frac = String(ash % ASH).padStart(8, '0').slice(0, maxDp).replace(/0+$/, '');
  return (neg ? '-' : '') + whole.toLocaleString('en-US') + (frac ? '.' + frac : '');
}
export function parseCndr(s) {
  s = String(s).trim();
  if (!/^\d+(\.\d{1,8})?$/.test(s)) return null;
  const [w, f = ''] = s.split('.');
  const v = BigInt(w) * 100000000n + BigInt(f.padEnd(8, '0'));
  return v > 0n && v < 2n ** 53n ? Number(v) : null;
}

// ------------------------------------------------------------------ the wallet's lock
// The backup words are stored on this device encrypted with the 3-digit SPP. The key
// derivation is deliberately slow, so every guess costs real time.
async function deriveKey(secret, salt, usage, iterations = 210_000) {
  const base = await crypto.subtle.importKey('raw', enc.encode(secret), 'PBKDF2', false, ['deriveKey', 'deriveBits']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' },
    base, { name: 'AES-GCM', length: 256 }, false, usage);
}
const SPP_ROUNDS = 600_000;
export async function lockEntropy(entropy, spp) {
  const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(`spp2|${normalizeSpp(spp)}`, salt, ['encrypt'], SPP_ROUNDS);
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, entropy);
  return { v: 2, salt: hex(salt), iv: hex(iv), ct: hex(new Uint8Array(ct)) };
}
export async function unlockEntropy(box, spp) {
  if (box.v !== 2) throw new Error('old card format');
  const key = await deriveKey(`spp2|${normalizeSpp(spp)}`, unhex(box.salt), ['decrypt'], SPP_ROUNDS);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unhex(box.iv) }, key, unhex(box.ct)));
}

// ---- cards saved before the PIN was dropped: PIN + SPP locked the words, the PIN alone locked the SPP.
export async function unlockEntropyLegacy(box, pin, spp) {
  const key = await deriveKey(`${pin}|${normalizeSpp(spp)}`, unhex(box.salt), ['decrypt']);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unhex(box.iv) }, key, unhex(box.ct)));
}
export async function unlockSppLegacy(sppBox, pin) {
  const key = await deriveKey(`spp|${pin}`, unhex(sppBox.salt), ['decrypt']);
  return new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unhex(sppBox.iv) }, key, unhex(sppBox.ct)));
}
// Oldest cards kept no SPP box: with the PIN known, try every 3-digit SPP.
export async function findSppLegacy(box, pin, onProgress) {
  for (let i = 0; i < 1000; i++) {
    const spp = String(i).padStart(3, '0');
    try { await unlockEntropyLegacy(box, pin, spp); return spp; } catch {}
    if (onProgress && i % 10 === 0) onProgress(i / 1000);
  }
  return null;
}
export const normalizeSpp = s => String(s).toUpperCase().replace(/\s/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
