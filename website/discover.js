// Finds the network's node from the address the node phone posts.
// Only posts signed with the network key count; anything else is ignored.
const CHAIN_ID = 'cinder-testnet-3';
const enc = new TextEncoder();
const unhex = h => new Uint8Array(h.match(/../g).map(x => parseInt(x, 16)));

function derToRaw(der) {           // ECDSA signature: DER -> 64-byte r||s
  let i = 2;
  if (der[1] & 0x80) i += der[1] & 0x7f;
  const out = new Uint8Array(64);
  for (const k of [0, 1]) {
    if (der[i++] !== 0x02) throw new Error('bad signature');
    let len = der[i++], v = der.slice(i, i + len); i += len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    out.set(v, 32 * k + 32 - v.length);
  }
  return out;
}

export async function verifyPost(m, keyHex) {
  try {
    const key = await crypto.subtle.importKey('raw', unhex(keyHex), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const sig = derToRaw(Uint8Array.from(atob(m.sig), c => c.charCodeAt(0)));
    return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, sig,
      enc.encode(`cinder-node|${CHAIN_ID}|${m.url}|${m.t}`));
  } catch { return false; }
}

// Every correctly signed address from the last day, newest first. Nodes come and go,
// so callers try them in order until one answers.
export async function allValid(ndjson, keyHex, now = Date.now() / 1000) {
  const seen = new Map();
  for (const line of ndjson.split('\n')) {
    try {
      const ev = JSON.parse(line);
      if (ev.event !== 'message') continue;
      const m = JSON.parse(ev.message);
      if (typeof m.url !== 'string' || !m.url.startsWith('https://') || typeof m.t !== 'number') continue;
      if (m.t > now + 600 || m.t < now - 86400) continue;
      if (!(await verifyPost(m, keyHex))) continue;
      const url = m.url.replace(/\/$/, '');
      if (!seen.has(url) || seen.get(url) < m.t) seen.set(url, m.t);
    } catch {}
  }
  return [...seen].sort((a, b) => b[1] - a[1]).map(([url]) => url);
}

export async function newestValid(ndjson, keyHex, now) {
  return (await allValid(ndjson, keyHex, now))[0] || null;
}

export async function discoverNodes(cfg) {
  if (!cfg?.topic || !crypto.subtle) return [];
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 8000);
  try {
    const r = await fetch(`${cfg.relay}/${cfg.topic}/json?poll=1&since=24h`, { signal: ctl.signal, cache: 'no-store' });
    return r.ok ? await allValid(await r.text(), cfg.key) : [];
  } catch { return []; } finally { clearTimeout(t); }
}

export async function discoverNode(cfg) { return (await discoverNodes(cfg))[0] || null; }
