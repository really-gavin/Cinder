// Cinder website: home, card wallet, jobs, loans, explorer.
import * as X from './cindercrypto.js';
import qrcode from './vendor/qrcode.js';
import { NODES, DISCOVERY } from './config.js';
import { discoverNodes } from './discover.js';

const { fmtCndr, parseCndr, formatId, normalizeId, isValidId } = X;
const INITIAL_REWARD = 1, COOLING = 525_600, DAY = 1440, REVIEW = DAY, MAX_REJECTS = 2;
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

// ------------------------------------------------------------------ helpers
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function ago(t) {
  if (!t) return '';
  const d = Math.max(0, Date.now() / 1000 - t);
  if (d < 60) return 'just now';
  if (d < 3600) return `${Math.floor(d / 60)}m ago`;
  if (d < 86400) return `${Math.floor(d / 3600)}h ago`;
  return `${Math.floor(d / 86400)}d ago`;
}
function span(blocks) {      // blocks -> "3 days", "5 hours", "12 minutes"
  const m = Math.max(0, blocks);
  if (m >= 2 * DAY) return `${Math.round(m / DAY)} days`;
  if (m >= 120) return `${Math.round(m / 60)} hours`;
  return `${m} minute${m === 1 ? '' : 's'}`;
}
const until = h => status ? (h > status.height ? `in ${span(h - status.height)}` : 'now') : '';
function si(n, unit) {
  const u = ['', 'k', 'M', 'G', 'T', 'P'];
  let i = 0; while (n >= 1000 && i < u.length - 1) { n /= 1000; i++; }
  return `${n.toFixed(n < 10 && i ? 2 : n < 100 && i ? 1 : 0)}<small> ${u[i]}${unit}</small>`;
}
const idLink = id => id ? `<a class="mono-id" href="#id-${esc(id)}">${esc(formatId(id))}</a>` : '<span class="muted">nobody</span>';
const store = {
  get(k) { try { return JSON.parse(localStorage.getItem('cinder.' + k)); } catch { return null; } },
  set(k, v) { try { localStorage.setItem('cinder.' + k, JSON.stringify(v)); } catch {} },
  del(k) { try { localStorage.removeItem('cinder.' + k); } catch {} },
};
function copy(text, btn) {
  const done = () => { const t = btn.textContent; btn.textContent = 'Copied'; setTimeout(() => (btn.textContent = t), 1400); };
  navigator.clipboard?.writeText(text).then(done, () => { btn.textContent = text; });
}
const days = v => { const d = Number(v); return Number.isFinite(d) && d > 0 ? Math.round(d * DAY) : null; };

// ------------------------------------------------------------------ node API
let API = null, status = null;
async function api(path, opts = {}, base = API, ms = 10000) {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(base + path, { ...opts, signal: ctl.signal,
      headers: opts.body ? { 'Content-Type': 'application/json' } : undefined });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || `node said ${r.status}`);
    return j;
  } finally { clearTimeout(t); }
}
let lastFind = 0, finding = null;
// Looks for a working node: the one you chose, the addresses node phones have announced
// (newest first), this site's own address, and localhost. All are asked at once and the
// first that answers on this chain wins, so a dead address never holds things up.
function findNode() {
  return finding ||= (async () => {
    lastFind = Date.now();
    const saved = store.get('node');
    const announced = NODES.length ? [] : await discoverNodes(DISCOVERY);
    const tries = [saved, ...NODES, ...announced.slice(0, 6), location.protocol.startsWith('http') ? location.origin : null, 'http://localhost:8642']
      .filter(Boolean).map(b => b.replace(/\/$/, ''));
    const list = [...new Set(tries)];
    const probe = async base => {
      const s = await api('/api/status', {}, base, 6000);
      if (!s || (s.chain_id && s.chain_id !== X.CHAIN_ID)) throw new Error('other chain');
      return [base, s];
    };
    // Earlier entries win when several answer, but nobody waits for the slow ones.
    const results = list.map(b => probe(b).then(r => r, () => null));
    for (const p of results) {
      const r = await p;
      if (r) { API = r[0]; status = r[1]; return true; }
    }
    API = (saved || list[0] || 'http://localhost:8642').replace(/\/$/, '');
    status = null;
    return false;
  })().finally(() => { finding = null; });
}

// ------------------------------------------------------------------ home
let lastTip = null;
function paintStatus() {
  $('#netpill').dataset.on = status ? '1' : '0';
  $('#netlabel').textContent = status ? `Block ${status.height.toLocaleString()}` : (finding ? 'Looking…' : 'No node');
  $('#foot-node').textContent = status ? `Reading from ${API}` : `Can't reach a node at ${API}`;
    if (!status) return;
  $('#s-height').textContent = status.height.toLocaleString();
  $('#s-supply').innerHTML = `${fmtCndr(status.supply, 0)} <small>CDR</small>`;
  $('#s-reward').innerHTML = `${fmtCndr(status.next_reward, 3)} <small>CDR</small>`;
  $('#s-hash').innerHTML = si(status.hashrate, 'H/s');
}
async function paintWall() {
  let blocks = [];
  if (status) { try { blocks = await api('/api/blocks?limit=11'); } catch {} }
  const fresh = status && lastTip && status.tip !== lastTip;
  const items = [{ ghost: true, height: status ? status.height + 1 : 1 }, ...blocks];
  while (items.length < 12) items.push({ empty: true });
  let html = '';
  for (let c = 0; c < 4; c++) {
    html += `<div class="course${c % 2 ? ' offset' : ''}">` + items.slice(c * 3, c * 3 + 3).map((b, i) => {
      if (b.ghost) return `<div class="brick ghost"><b>#${b.height}</b><span>${status ? 'being mined now' : 'connect a node'}</span></div>`;
      if (b.empty) return `<div class="brick ghost" aria-hidden="true"><b>&nbsp;</b><span>&nbsp;</span></div>`;
      const top = c === 0 && i === 1;
      return `<a class="brick${top ? ' fresh' : ''}${top && fresh ? ' just-laid' : ''}" href="#block-${b.height}"><b>#${b.height}</b><span>${b.hash.slice(0, 14)} · ${ago(b.time)}</span></a>`;
    }).join('') + '</div>';
  }
  $('#wall').innerHTML = html;
  $('#wallcap').textContent = status ? 'The wall above is the live chain. Newest block on top, highlighted. Tap one to inspect it.'
    : 'Connect to a node to see the live chain laid out here.';
  lastTip = status?.tip ?? null;
}
function paintCurve() {
  const W = 640, H = 280, L = 44, R = 40, T = 22, B = 38, years = 10;
  const x = y => L + (y / years) * (W - L - R), y = r => T + (1 - r / INITIAL_REWARD) * (H - T - B);
  const pts = [];
  for (let e = 0; e < years; e++) { const base = INITIAL_REWARD / 2 ** e; pts.push([e, base], [e + 1, base / 2]); }
  const line = pts.map(([a, b], i) => `${i ? 'L' : 'M'}${x(a).toFixed(1)},${y(b).toFixed(1)}`).join('');
  let g = '';
  for (const r of [0, .25, .5, .75, 1]) g += `<line class="axis" x1="${L}" x2="${W - R}" y1="${y(r)}" y2="${y(r)}" stroke-opacity="${r ? .5 : 1}"/><text x="${L - 8}" y="${y(r) + 5}" text-anchor="end">${r}</text>`;
  for (let yr = 0; yr <= years; yr += 2) g += `<text x="${x(yr)}" y="${H - 12}" text-anchor="middle">${yr === 0 ? 'Launch' : `Year ${yr}`}</text>`;
  let now = '';
  if (status && status.height / COOLING <= years) {
    const xx = x(status.height / COOLING);
    now = `<line class="now" x1="${xx}" x2="${xx}" y1="${T}" y2="${y(0)}"/><text x="${xx + 8}" y="${T + 22}" style="fill:var(--ink)">You are here</text>`;
  }
  $('#curve').innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Block reward starts at 1 CDR and slides to half each year">
    ${g}<path class="area" d="${line}L${x(years)},${y(0)}L${x(0)},${y(0)}Z"/><path class="line" d="${line}"/>${now}
    <text x="${L - 8}" y="${T - 8}" text-anchor="end" style="font-size:12px">CDR</text></svg>`;
}

// ------------------------------------------------------------------ the card (wallet state)
// stored: { id, pub, pin: {salt, hash}, box: {salt, iv, ct} }
const card = { saved: store.get('card'), unlocked: false, pin: null, me: null, draft: null };
const hasWallet = () => !!card.saved;
async function loadMe() {
  if (!card.saved || !status) return null;
  try { card.me = await api('/api/id/' + card.saved.id); } catch { card.me = null; }
  return card.me;
}

function cardFront(id, foot = '') {
  return `<div class="card" aria-label="Cinder card ${esc(formatId(id))}">
    <div class="top"><b>CINDER</b><svg viewBox="0 0 34 24" aria-hidden="true"><path fill="#f3d317" fill-rule="evenodd" d="M0 0h34v24H0zM5 5v14h10V5zm14 0v14h10V5z"/></svg></div>
    <div class="cardnum">${esc(formatId(id))}</div>
    <div class="foot"><span>Card ID</span><span>${foot}</span></div></div>`;
}
function cardBack(spp) {
  return `<div class="card back" aria-label="Back of card"><div class="stripe-mag"></div>
    <div class="strip">${esc(spp)}</div><div class="foot" style="align-self:end"><span>SPP: confirms every payment</span><span>Keep it to yourself</span></div></div>`;
}

// Ask for the SPP, unlock the key, run fn(keys, progress). Resolves to fn's result or null.
function withSpp(what, fn) {
  return new Promise(resolve => {
    const dlg = $('#sppdlg'), input = $('#spp-in'), msg = $('#spp-msg'), ok = $('#spp-ok');
    $('#spp-what').innerHTML = what;
    input.value = ''; msg.innerHTML = ''; ok.disabled = false; ok.textContent = 'Confirm';
    dlg.showModal(); input.focus();
    const find = $('#spp-find'), found = $('#spp-found'), pinIn = $('#spp-pin'), reveal = $('#spp-reveal');
    find.hidden = true; found.innerHTML = '';
    $('#spp-show').onclick = () => { find.hidden = false; pinIn.value = ''; pinIn.focus(); };
    pinIn.onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); reveal.click(); } };
    reveal.onclick = async () => {
      const wait = (card.lockUntil || 0) - Date.now();
      if (wait > 0) return void (found.innerHTML = `<div class="msg bad">Too many wrong tries. Wait ${Math.ceil(wait / 1000)} seconds.</div>`);
      const h = await X.pinHash(pinIn.value, card.saved.pin.salt);
      if (h.hash !== card.saved.pin.hash) {
        card.fails = (card.fails || 0) + 1;
        if (card.fails >= 5) card.lockUntil = Date.now() + 30_000 * (card.fails - 4);
        return void (found.innerHTML = `<div class="msg bad">Wrong PIN.</div>`);
      }
      card.fails = 0;
      reveal.disabled = true;
      try {
        let spp = null;
        if (card.saved.sppBox) { try { spp = await X.unlockSpp(card.saved.sppBox, pinIn.value); } catch {} }
        if (!spp) {
          found.innerHTML = `<div style="display:grid;gap:6px"><span class="muted">Looking it up. This can take up to a minute.</span><div class="progress"><i id="spp-bar" style="width:0%"></i></div></div>`;
          spp = await X.findSpp(card.saved.box, pinIn.value, p => { const b = $('#spp-bar'); if (b) b.style.width = Math.round(p * 100) + '%'; });
          if (spp) { card.saved.sppBox = await X.lockSpp(spp, pinIn.value); store.set('card', card.saved); }
        }
        if (!spp) return void (found.innerHTML = `<div class="msg warn">This card was made before 3-digit SPPs, so it can’t be looked up. Use your 12 words instead.</div>`);
        found.innerHTML = `<div class="msg"><span class="muted">Your SPP is</span> <b style="font:800 34px var(--display);letter-spacing:.1em">${esc(spp)}</b></div>`;
        input.value = spp; msg.innerHTML = '';
      } finally { reveal.disabled = false; }
    };
    $('#spp-forgot').onclick = () => { dlg.close(); resolve(null); card.recover = true; location.hash = '#wallet'; renderWallet(); };
    $('#sppform').onsubmit = async e => {
      if (e.submitter?.value !== 'ok') { resolve(null); return; }
      e.preventDefault();
      let keys;
      ok.disabled = true; ok.textContent = 'Checking…';
      try { keys = X.keysFromEntropy(await X.unlockEntropy(card.saved.box, card.pin, input.value)); }
      catch { msg.innerHTML = `<div class="msg bad">That SPP doesn't match this card. It's the 3-digit code on the back of your card. Forgot it? Use “Get it back with your 12 words” above.</div>`; ok.disabled = false; ok.textContent = 'Confirm'; return; }
      const progress = p => { msg.innerHTML = `<div style="display:grid;gap:6px"><span class="muted">Doing a second of work instead of a fee…</span><div class="progress"><i style="width:${Math.round(p * 100)}%"></i></div></div>`; };
      progress(0.02);
      try { const r = await fn(keys, progress); dlg.close(); resolve(r); }
      catch (err) { msg.innerHTML = `<div class="msg bad">${esc(err.message)}</div>`; ok.disabled = false; ok.textContent = 'Try again'; }
    };
    dlg.oncancel = () => resolve(null);
  });
}
// Sign and send one transaction from this card.
async function submit(keys, fields, progress) {
  const me = await api('/api/id/' + keys.id);
  if (!me.registered && !me.registering && fields.type !== 'register') throw new Error('This card isn’t on the network yet. Finish setting it up on the wallet page.');
  const tx = await X.buildTx(keys.priv, { ...fields, from: keys.id }, fields.type === 'register' ? 0 : me.next_nonce, 0, progress);
  const res = await api('/api/tx', { method: 'POST', body: JSON.stringify(tx) });
  await loadMe();
  return res.txid;
}
const act = (what, fields) => withSpp(what, (keys, p) => submit(keys, fields, p));

// ------------------------------------------------------------------ wallet page
function renderWallet() {
  const root = $('#wallet-root');
  if (!window.isSecureContext || !crypto.subtle) {
    root.innerHTML = `<div class="page-head"><h2>Wallet.</h2></div><div class="msg bad">The wallet needs a secure page to make and protect keys. Open this site over https, or at http://localhost on the machine running the node.</div>`;
    return;
  }
  if (card.importing || card.importError) return importScreen(root);
  if (!hasWallet() || card.recover) return setupStart(root);
  if (!card.unlocked) return pinScreen(root);
  walletMain(root);
}

function setupStart(root) {
  root.innerHTML = `<div class="page-head"><h2>${card.recover ? 'Forgot your PIN or SPP?' : 'Get your Cinder card.'}</h2>
    <p class="lede">${card.recover ? 'Type your 12 backup words and choose a new PIN. You get back in, and your SPP is shown again. Your card ID and balance don’t change.' : 'Your card lives on this device. You choose a PIN; the card comes with a 3-digit SPP for payments and 12 backup words in case you lose the device.'}</p>
    ${card.intent ? `<div class="msg warn" style="display:grid;gap:10px">A shop asked you to pay ${fmtCndr(card.intent.amount)} CDR.
      ${isAndroid ? `<span>Have Cinder Wallet on this phone? Pay with it. Otherwise make a card here, or bring yours back with your 12 words.</span>
      <div class="row"><a class="btn hi sm" href="${esc(appPayLink(card.intent))}">Pay with Cinder Wallet</a><a class="btn ghost sm" href="phone/#wallet">Get the app</a></div>`
      : `You need a card with CDR on it first. Already have one on another device? Pay from there, or bring it here with your 12 words.${isIOS && !standalone ? `
      <span>Your card is in the Cinder app on your Home Screen? Copy this request, open the app, and tap Paste payment request.</span>
      <div class="row"><button class="btn ghost sm" type="button" data-copy-req>Copy request</button></div>` : ''}`}</div>` : ''}</div>
    <div class="cols">
      ${card.recover ? '' : `<form class="panel heavy" id="new-card"><h3>Make a new card</h3>
        <label class="f" for="pin1">Choose a 6-digit PIN<input id="pin1" class="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autocomplete="new-password"></label>
        <label class="f" for="pin2">Same PIN again<input id="pin2" class="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autocomplete="new-password"></label>
        <div id="new-msg"></div><div><button class="btn hi" type="submit">Make my card</button></div></form>`}
      <form class="panel${card.recover ? ' heavy' : ''}" id="restore-card"><h3>Bring back a card</h3>
        <label class="f" for="words">Your 12 backup words<textarea id="words" rows="3" required spellcheck="false" autocapitalize="none" style="font:16px var(--mono);padding:12px;border:1.5px solid var(--mortar);background:var(--concrete);color:var(--ink)"></textarea></label>
        <label class="f" for="rpin">Choose a 6-digit PIN for this device<input id="rpin" class="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autocomplete="new-password"></label>
        <div id="restore-msg"></div><div><button class="btn" type="submit">Restore card</button></div>
        <p class="muted" style="font-size:14px">Your SPP comes back too: it's worked out from the words. (A card made before 3-digit SPPs gets a new 3-digit one.)</p>
        ${card.recover ? `<div><button class="btn ghost sm" type="button" id="recover-cancel">Never mind</button></div>` : ''}</form>
    </div>`;
  const rc = $('#recover-cancel'); if (rc) rc.onclick = () => { card.recover = false; renderWallet(); };
  if ($('#new-card')) $('#new-card').onsubmit = e => {
    e.preventDefault();
    const a = $('#pin1').value, b = $('#pin2').value;
    if (!/^\d{6}$/.test(a)) return void ($('#new-msg').innerHTML = `<div class="msg bad">The PIN is exactly 6 digits.</div>`);
    if (a !== b) return void ($('#new-msg').innerHTML = `<div class="msg bad">The two PINs don't match.</div>`);
    setupReveal(root, X.newEntropy(), a, true);
  };
  $('#restore-card').onsubmit = e => {
    e.preventDefault();
    let ent;
    try { ent = X.entropyFromPhrase($('#words').value); } catch (err) { return void ($('#restore-msg').innerHTML = `<div class="msg bad">${esc(err.message)}</div>`); }
    if (!/^\d{6}$/.test($('#rpin').value)) return void ($('#restore-msg').innerHTML = `<div class="msg bad">The PIN is exactly 6 digits.</div>`);
    if (card.recover && card.saved && X.keysFromEntropy(ent).id !== card.saved.id)
      return void ($('#restore-msg').innerHTML = `<div class="msg bad">Those words belong to a different card, not ${formatId(card.saved.id)}. Your saved card is unchanged.</div>`);
    setupReveal(root, ent, $('#rpin').value, false);
  };
}

function setupReveal(root, entropy, pin, isNew) {
  const keys = X.keysFromEntropy(entropy), spp = X.sppFromEntropy(entropy);
  const words = X.phraseFromEntropy(entropy);
  root.innerHTML = `<div class="page-head"><h2>${isNew ? 'This is your card.' : 'Your card is back.'}</h2>
    <p class="lede">${isNew ? 'Write down your 3-digit SPP and the 12 words.' : 'Your SPP is below.'} If you ever forget your PIN or SPP, your 12 words bring them back.</p></div>
    <div class="cols">
      <div style="display:grid;gap:14px">${cardFront(keys.id, 'Testnet')}${cardBack(spp)}</div>
      <div class="panel heavy">
        <h3>${isNew ? 'Your 12 backup words' : 'Backup words'}</h3>
        ${isNew ? `<p>If you lose this device or forget your SPP, these words bring the card back, in this order. Anyone who has them can spend your CDR, so keep them offline and private.</p>
        <ol class="phrase">${words.map(w => `<li>${w}</li>`).join('')}</ol>` : `<p class="muted">You already have them. Nothing changes about your card ID or balance.</p>`}
        <label class="row" style="gap:10px;font-weight:600"><input type="checkbox" id="saved"> I wrote down my SPP${isNew ? ' and my 12 words' : ''}</label>
        <div id="reg-msg"></div>
        <div><button class="btn hi" type="button" id="finish" disabled>Finish setting up</button></div>
      </div>
    </div>`;
  $('#saved').onchange = e => { $('#finish').disabled = !e.target.checked; };
  $('#finish').onclick = async () => {
    const btn = $('#finish'), msg = $('#reg-msg');
    btn.disabled = true;
    card.saved = { id: keys.id, pub: keys.pub, pin: await X.pinHash(pin), box: await X.lockEntropy(entropy, pin, spp), sppBox: await X.lockSpp(spp, pin) };
    store.set('card', card.saved);
    Object.assign(card, { unlocked: true, pin, recover: false });
    try {
      const me = await api('/api/id/' + keys.id);
      if (!me.registered && !me.registering) {
        msg.innerHTML = `<div style="display:grid;gap:6px"><span class="muted">Putting your card on the network…</span><div class="progress"><i id="regbar"></i></div></div>`;
        await submit(keys, { type: 'register', pub: keys.pub }, p => { const b = $('#regbar'); if (b) b.style.width = Math.round(p * 100) + '%'; });
      }
    } catch (err) {
      card.setupError = err.message;
    }
    await loadMe();
    renderWallet();
  };
}

function pinScreen(root) {
  root.innerHTML = `<div class="page-head"><h2>Unlock your card.</h2>
    ${card.intent ? `<p class="lede">Unlock to pay ${fmtCndr(card.intent.amount)} CDR to the shop.</p>` : ''}</div>
    <form class="cols" id="unlock">
      ${cardFront(card.saved.id, 'Locked')}
      <div class="panel heavy">
        <label class="f" for="upin">PIN<input id="upin" class="pin" inputmode="numeric" maxlength="6" required autocomplete="current-password"></label>
        <div id="umsg"></div>
        <div class="row"><button class="btn hi" type="submit">Unlock</button><button class="btn ghost sm" type="button" id="forget">Remove from this device</button></div>
        <p class="muted" style="font-size:14px">Forgot your PIN? <button type="button" class="linkbtn" id="forgot-pin">Get back in with your 12 words</button>. Your card and balance stay as they are.</p>
      </div></form>`;
  $('#upin').focus();
  $('#unlock').onsubmit = async e => {
    e.preventDefault();
    const pin = $('#upin').value;
    const wait = (card.lockUntil || 0) - Date.now();
    if (wait > 0) return void ($('#umsg').innerHTML = `<div class="msg bad">Too many wrong tries. Wait ${Math.ceil(wait / 1000)} seconds.</div>`);
    const h = await X.pinHash(pin, card.saved.pin.salt);
    if (h.hash !== card.saved.pin.hash) {
      card.fails = (card.fails || 0) + 1;
      if (card.fails >= 5) { card.lockUntil = Date.now() + 30_000 * (card.fails - 4); }
      return void ($('#umsg').innerHTML = `<div class="msg bad">Wrong PIN.</div>`);
    }
    card.fails = 0;
    Object.assign(card, { unlocked: true, pin });
    await loadMe();
    renderWallet();
  };
  $('#forget').onclick = () => confirmForget($('#umsg'));
  $('#forgot-pin').onclick = () => { card.recover = true; renderWallet(); };
}

// A card arriving from the app: pick a PIN for this browser and it's the same card, same SPP.
function importScreen(root) {
  if (card.importError) {
    card.importError = false;
    root.innerHTML = `<div class="page-head"><h2>That link didn’t work.</h2><p class="lede">It’s missing words, or one is wrong. Open Cinder Wallet and try “Use on the website” again.</p></div>`;
    return;
  }
  const ent = card.importing, id = X.keysFromEntropy(ent).id;
  if (card.saved && card.saved.id === id) {
    card.importing = null;
    root.innerHTML = `<div class="page-head"><h2>This card is already here.</h2><p class="lede">${formatId(id)} is saved on this browser. Unlock it with your PIN.</p><div><a class="btn hi" href="#wallet" id="imp-ok">Go to my card</a></div></div>`;
    $('#imp-ok').onclick = e => { e.preventDefault(); renderWallet(); };
    return;
  }
  root.innerHTML = `<div class="page-head"><h2>Add your card here.</h2>
    <p class="lede">This brings your card from the Cinder Wallet app to this browser. Same card, same balance, same SPP.${card.saved ? ` <b>It replaces the card saved here (${formatId(card.saved.id)}).</b> Make sure you have its 12 words first.` : ''}</p></div>
    <form class="cols" id="imp">
      ${cardFront(id, 'Moving here')}
      <div class="panel heavy">
        <label class="f" for="ipin">Choose a 6-digit PIN for this browser<input id="ipin" class="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autocomplete="new-password"></label>
        <div id="imsg"></div>
        <div class="row"><button class="btn hi" type="submit">Add card</button><button class="btn ghost sm" type="button" id="icancel">Cancel</button></div>
      </div></form>`;
  $('#ipin').focus();
  $('#icancel').onclick = () => { card.importing = null; renderWallet(); };
  $('#imp').onsubmit = e => {
    e.preventDefault();
    const pin = $('#ipin').value;
    if (!/^\d{6}$/.test(pin)) return void ($('#imsg').innerHTML = `<div class="msg bad">The PIN is exactly 6 digits.</div>`);
    card.importing = null;
    setupReveal(root, ent, pin, false);
  };
}

// Show the 12 words as a link and a QR code, for opening the same card in the app (or another browser).
async function transferPanel(slot) {
  const words = await withSpp('Moving your card to another device shows your 12 words. Anyone who scans this can spend your CDR.', async () => {
    return X.phraseFromEntropy(await X.unlockEntropy(card.saved.box, card.pin, $('#spp-in').value));
  });
  if (!words) return;
  const joined = words.join('.');
  const site = location.origin + location.pathname;
  const appLink = `intent://import?words=${joined}#Intent;scheme=cinderpay;package=app.cinder.wallet;end`;
  const webLink = `${site}#import=${joined}`;
  slot.innerHTML = `<div class="panel heavy" style="gap:12px"><h3>Use this card on another device</h3>
    <div class="msg warn">Only scan or open this on your own phone. It carries your 12 words, so it is your card.</div>
    <figure class="qr">${qrSvg(webLink)}<figcaption class="muted">Scan with the phone’s camera.</figcaption></figure>
    <div class="row">${isAndroid ? `<a class="btn hi sm" href="${esc(appLink)}">Open in Cinder Wallet</a>` : ''}<button class="btn sm" type="button" id="xfer-copy">Copy link</button><button class="btn ghost sm" type="button" id="xfer-hide">Hide</button></div></div>`;
  $('#xfer-copy').onclick = e => copy(webLink, e.currentTarget);
  $('#xfer-hide').onclick = () => { slot.innerHTML = ''; };
}

function confirmForget(slot) {
  slot.innerHTML = `<div class="msg warn" style="display:grid;gap:10px">This removes the card from this device. Without your 12 backup words, the CDR on it is gone for good.
    <div><button class="btn sm" type="button" id="forget-yes">Remove it</button></div></div>`;
  $('#forget-yes').onclick = () => { store.del('card'); Object.assign(card, { saved: null, unlocked: false, pin: null, me: null }); renderWallet(); };
}

function walletMain(root) {
  root.innerHTML = `
    <div id="w-intent"></div>
    <div class="cols" style="align-items:center">
      <div id="w-cardslot" class="flipslot" role="button" tabindex="0" aria-label="Flip the card to see your SPP">${cardFront(card.saved.id, 'Testnet')}</div>
      <div style="display:grid;gap:10px">
        <p class="muted">Balance</p>
        <div class="balance" id="w-bal">…<small>CDR</small></div>
        <div id="w-state"></div>
        <div class="row"><button class="btn sm" type="button" id="w-copy">Copy card ID</button><button class="btn sm" type="button" id="w-flip">Show SPP</button><a class="btn ghost sm" href="#id-${esc(card.saved.id)}">View in explorer</a></div>
        <div id="w-sppmsg"></div>
      </div>
    </div>
    <div class="tiles">
      <button class="btn hi" type="button" id="t-send">Send</button>
      <button class="btn" type="button" id="t-recv">Receive</button>
      <a class="btn" href="#checkout">Take payments</a>
      <button class="btn" type="button" id="t-words">Backup words</button>
    </div>
    <div id="w-panel"></div>
    <form class="panel heavy" id="w-send" autocomplete="off">
      <h3>Send CDR</h3>
      <div class="two">
        <label class="f" for="s-to">To card ID<input id="s-to" required placeholder="XXXX XXXX XXXX XXXX" spellcheck="false" autocapitalize="characters" class="mono"></label>
        <label class="f" for="s-amt">Amount (CDR)<input id="s-amt" required inputmode="decimal" placeholder="0.00"></label>
      </div>
      <label class="f" for="s-memo">Note <span class="hint">Optional and public, up to 80 characters.</span><input id="s-memo" maxlength="80"></label>
      <div id="s-msg"></div>
      <div><button class="btn hi" type="submit">Send</button></div>
    </form>
    <div class="panel"><h3>Activity</h3><div class="scroll" id="w-hist"><p class="muted">Loading…</p></div></div>
    <div class="row">
      <button class="btn ghost sm" type="button" id="w-words">Show backup words</button>
      <button class="btn ghost sm" type="button" id="w-xfer">Use on another device</button>
      <button class="btn ghost sm" type="button" id="w-net">Network</button>
      <button class="btn ghost sm" type="button" id="w-lock">Lock</button>
      <button class="btn ghost sm" type="button" id="w-rm">Remove from this device</button>
    </div>
    <div id="w-foot"></div>`;
  $('#w-copy').onclick = e => copy(formatId(card.saved.id), e.currentTarget);
  // Flip the card to see the SPP on the back, like a real card. It flips back by itself.
  let flipTimer = null;
  const flipCard = async () => {
    const slot = $('#w-cardslot'), btn = $('#w-flip'), note = $('#w-sppmsg');
    if (!slot) return;
    clearTimeout(flipTimer); note.innerHTML = '';
    if (slot.dataset.side === 'back') {
      slot.innerHTML = cardFront(card.saved.id, 'Testnet'); slot.dataset.side = 'front'; btn.textContent = 'Show SPP';
      return;
    }
    btn.disabled = true;
    try {
      if (!card.spp || card.sppFor !== card.saved.id) {
        let spp = null;
        if (card.saved.sppBox) { try { spp = await X.unlockSpp(card.saved.sppBox, card.pin); } catch {} }
        if (!spp) {
          btn.textContent = 'Looking it up…';
          spp = await X.findSpp(card.saved.box, card.pin);
          if (spp) { card.saved.sppBox = await X.lockSpp(spp, card.pin); store.set('card', card.saved); }
        }
        if (!spp) { note.innerHTML = `<div class="msg warn">This card was made before 3-digit SPPs, so it can’t be shown here. Restore it from your 12 words to get one.</div>`; btn.textContent = 'Show SPP'; return; }
        card.spp = spp; card.sppFor = card.saved.id;
      }
      slot.innerHTML = cardBack(card.spp); slot.dataset.side = 'back'; btn.textContent = 'Hide SPP';
      flipTimer = setTimeout(() => { if (slot.dataset.side === 'back') flipCard(); }, 30_000);
    } finally { btn.disabled = false; }
  };
  $('#w-flip').onclick = flipCard;
  $('#w-cardslot').onclick = flipCard;
  $('#w-cardslot').onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); flipCard(); } };
  $('#w-lock').onclick = () => { Object.assign(card, { unlocked: false, pin: null, spp: null }); renderWallet(); };
  $('#w-rm').onclick = () => confirmForget($('#w-foot'));
  $('#w-xfer').onclick = () => transferPanel($('#w-foot'));
  $('#w-net').onclick = () => { $('#nodeurl').value = API || ''; $('#nodedlg').showModal(); };
  $('#t-words').onclick = () => $('#w-words').click();
  $('#t-send').onclick = () => { $('#w-send').scrollIntoView({ behavior: 'smooth', block: 'center' }); $('#s-to').focus({ preventScroll: true }); };
  $('#t-recv').onclick = () => {
    const slot = $('#w-panel');
    if (slot.innerHTML) { slot.innerHTML = ''; return; }
    slot.innerHTML = `<div class="panel" style="gap:12px;justify-items:center;text-align:center"><h3>Receive</h3>
      <p class="muted">Anyone with Cinder Wallet can scan this to pay you.</p>
      <figure class="qr">${qrSvg('cinderpay://pay?to=' + card.saved.id)}</figure>
      <div class="mono-id" style="font-size:22px">${formatId(card.saved.id)}</div>
      <div class="row"><button class="btn sm" type="button" id="r-copy">Copy card ID</button><button class="btn ghost sm" type="button" id="r-hide">Hide</button></div></div>`;
    $('#r-copy').onclick = e => copy(formatId(card.saved.id), e.currentTarget);
    $('#r-hide').onclick = () => { slot.innerHTML = ''; };
  };
  $('#w-words').onclick = async () => {
    const words = await withSpp('Your SPP unlocks the backup words.', async () => {
      const ent = await X.unlockEntropy(card.saved.box, card.pin, $('#spp-in').value);
      return X.phraseFromEntropy(ent);
    });
    if (words) $('#w-foot').innerHTML = `<div class="panel" style="gap:12px"><b>Your 12 backup words</b><ol class="phrase">${words.map(w => `<li>${w}</li>`).join('')}</ol><div><button class="btn sm" type="button" onclick="this.closest('.panel').remove()">Hide</button></div></div>`;
  };
  $('#w-send').onsubmit = sendFlow;
  refreshWallet();
  paintIntent();
  loadMe().then(refreshWallet);
}

function refreshWallet() {
  const me = card.me;
  if (!$('#w-bal')) return;
  if (!me) {
    $('#w-bal').innerHTML = `—<small>CDR</small>`;
    $('#w-hist').innerHTML = `<div class="msg bad">Can't reach a node. <a href="#" data-node>Pick a node</a></div>`;
    return;
  }
  $('#w-bal').innerHTML = `${fmtCndr(me.available, 4)}<small>CDR</small>`;
  let st = '';
  if (!me.registered && me.registering) st = `<div class="msg warn">Your card is being set up. It works once the next block is found, usually within a minute or two.</div>`;
  else if (!me.registered) st = `<div class="msg bad" style="display:grid;gap:8px">This card isn't on the network yet${card.setupError ? ` (${esc(card.setupError)})` : ''}.<div><button class="btn sm" type="button" id="w-reg">Finish setting up</button></div></div>`;
  const owing = me.owes.reduce((n, l) => n + l.owed, 0);
  if (owing) st += `<p>You owe ${fmtCndr(owing)} CDR on loans. <a href="#loans">See loans</a></p>`;
  if (me.available !== me.balance) st += `<p class="muted">${fmtCndr(Math.abs(me.balance - me.available))} CDR ${me.available < me.balance ? 'on its way out' : 'on its way in'}, waiting for the next block.</p>`;
  $('#w-state').innerHTML = st;
  $('#w-reg')?.addEventListener('click', async () => {
    card.setupError = null;
    const r = await withSpp('Putting your card on the network takes a few seconds of work.', (keys, p) => submit(keys, { type: 'register', pub: keys.pub }, p));
    if (r) refreshWallet();
  });
  $('#w-hist').innerHTML = activity(me, card.saved.id);
}

const WHY = {
  payment: (e, me) => e.to === me ? `From ${idLink(e.from)}` : `To ${idLink(e.to)}`,
  mined: () => 'Mined a block',
  'job escrow': e => `Locked as pay for a <a href="#job-${esc(e.ref)}">job</a>`,
  'job refund': e => `Refund from a <a href="#job-${esc(e.ref)}">job</a>`,
  'job pay': (e, me) => e.to === me ? `Paid for a <a href="#job-${esc(e.ref)}">job</a>` : `Paid out for a <a href="#job-${esc(e.ref)}">job</a>`,
  loan: (e, me) => e.to === me ? `<a href="#loan-${esc(e.ref)}">Loan</a> from ${idLink(e.from)}` : `<a href="#loan-${esc(e.ref)}">Loan</a> to ${idLink(e.to)}`,
  'loan repay': (e, me) => `<a href="#loan-${esc(e.ref)}">Loan</a> repayment ${e.to === me ? 'from ' + idLink(e.from) : 'to ' + idLink(e.to)}`,
  'auto-repay': (e, me) => `Automatic <a href="#loan-${esc(e.ref)}">loan</a> repayment ${e.to === me ? 'from ' + idLink(e.from) : 'to ' + idLink(e.to)}`,
  'loan due': (e, me) => `<a href="#loan-${esc(e.ref)}">Loan</a> collected ${e.to === me ? 'from ' + idLink(e.from) : 'by ' + idLink(e.to)}`,
};
const ACTION = {
  register: 'Card set up', job_post: 'Posted a job', job_cancel: 'Cancelled a job', job_take: 'Took a job',
  job_submit: 'Handed in a job', job_approve: 'Approved a job', job_reject: 'Sent a job back',
  loan_request: 'Asked for a loan', loan_cancel: 'Withdrew a loan request', loan_fund: 'Funded a loan',
  loan_repay: 'Repaid a loan', loan_close: 'Closed a loan', pay: 'Payment',
};
function actionLink(t) {
  const ref = t.job ? `#job-${t.job}` : t.loan ? `#loan-${t.loan}` : t.type === 'job_post' ? `#job-${t.txid}` : t.type === 'loan_request' ? `#loan-${t.txid}` : `#tx-${t.txid}`;
  return `<a href="${esc(ref)}">${ACTION[t.type] || t.type}</a>${t.from !== viewing ? ` by ${idLink(t.from)}` : ''}`;
}
let viewing = null;
function activity(me, id) {
  viewing = id;
  const rows = [
    ...me.pending.map(t => `<tr><td><span class="chip go">Waiting</span></td><td>${t.type === 'pay' ? (t.from === id ? `To ${idLink(t.to)}` : `From ${idLink(t.from)}`) : ACTION[t.type]}${t.memo ? ` <span class="muted">“${esc(t.memo)}”</span>` : ''}</td>
      <td class="num ${t.type === 'pay' ? (t.from === id ? 'out' : 'in') : ''}">${t.type === 'pay' ? (t.from === id ? '-' : '+') + fmtCndr(t.amount, 4) : ''}</td></tr>`),
    ...me.history.map(e => {
      if (e.kind === 'action') return `<tr><td>${ago(e.time)}</td><td>${actionLink(e)}</td><td></td></tr>`;
      const inn = e.to === id;
      return `<tr><td>${ago(e.time)}</td><td>${(WHY[e.why] || (() => e.why))(e, id)}${e.memo ? ` <span class="muted">“${esc(e.memo)}”</span>` : ''}</td>
        <td class="num ${inn ? 'in' : 'out'}">${inn ? '+' : '-'}${fmtCndr(e.amount, 4)}</td></tr>`;
    })];
  if (!rows.length) return `<div class="empty"><b>Nothing here yet.</b><span class="muted">Everyone starts at zero. Take a job, get paid by someone, or mine a block.</span><a class="btn sm" href="#jobs">Find a job</a></div>`;
  return `<table class="ledger act"><thead><tr><th>When</th><th>What</th><th class="num">CDR</th></tr></thead><tbody>${rows.slice(0, 60).join('')}</tbody></table>`;
}

async function sendFlow(e) {
  e.preventDefault();
  const msg = $('#s-msg'), bad = t => { msg.innerHTML = `<div class="msg bad">${t}</div>`; };
  const to = normalizeId($('#s-to').value), amount = parseCndr($('#s-amt').value), memo = $('#s-memo').value;
  if (!isValidId(to)) return bad("That card ID isn't right. It's 16 letters and numbers, and one of them looks mistyped.");
  if (to === card.saved.id) return bad("That's your own card.");
  if (!amount) return bad('Enter an amount, like 2.5. Up to 8 decimal places.');
  await loadMe();
  if (!card.me) return bad("Can't reach the node to check your balance.");
  if (amount > card.me.available) return bad(`You have ${fmtCndr(card.me.available)} CDR available.`);
  let them;
  try { them = await api('/api/id/' + to); } catch (err) { return bad(esc(err.message)); }
  if (!them.registered && !them.registering) return bad(`${formatId(to)} isn't a card on the network. Check the ID with the person you're paying.`);
  msg.innerHTML = '';
  const txid = await act(`Send <b>${fmtCndr(amount)} CDR</b> to <span class="mono-id">${formatId(to)}</span>${memo ? ` with the note “${esc(memo)}”` : ''}. Payments can't be reversed.`,
    { type: 'pay', to, amount, memo });
  if (txid) {
    $('#w-send').reset();
    msg.innerHTML = `<div class="msg good">Sent. It lands with the next block. <a href="#tx-${txid}">Receipt</a></div>`;
    refreshWallet();
  }
}

// ------------------------------------------------------------------ jobs page
const JOB_CHIP = {
  open: ['go', 'Open'], taken: ['dim', 'In progress'], submitted: ['dim', 'Waiting for review'],
  paid: ['ok', 'Paid'], cancelled: ['dim', 'Cancelled'], expired: ['dim', 'Nobody took it'], missed: ['warn', 'Deadline missed'],
};
const chip = (map, s) => `<span class="chip ${map[s]?.[0] || 'dim'}">${map[s]?.[1] || s}</span>`;
function gate(what) {
  if (!window.isSecureContext) return `<div class="gate">To ${what}, open this site over https.</div>`;
  if (!hasWallet()) return `<div class="gate"><b>You need a Cinder card to ${what}.</b><a class="btn hi sm" href="#wallet">Get your card</a></div>`;
  if (!card.unlocked) return `<div class="gate"><b>Unlock your card to ${what}.</b><a class="btn sm" href="#wallet">Unlock</a></div>`;
  return '';
}
let jobsTab = 'open';
function renderJobs() {
  const root = $('#jobs-root');
  root.innerHTML = `<div class="page-head"><h2>Jobs.</h2>
    <p class="lede">Do something, get paid in CDR. The pay is locked on the network when the job is posted, so it's there when the work is done.</p></div>
    <div class="tabs" role="tablist">
      <button role="tab" data-tab="open" aria-selected="${jobsTab === 'open'}">Open jobs</button>
      <button role="tab" data-tab="mine" aria-selected="${jobsTab === 'mine'}">My jobs</button>
      <button role="tab" data-tab="post" aria-selected="${jobsTab === 'post'}">Post a job</button>
    </div>
    <div id="jobs-body"></div>
    <details><summary><b>How job pay works</b></summary><div style="display:grid;gap:8px;margin-top:10px;max-width:65ch">
      <p>Posting a job locks its pay on the network. If nobody takes it before it closes, the pay comes back to you.</p>
      <p>Once someone takes it, they have the time you set to hand it in. Miss that and the pay goes back to the poster.</p>
      <p>After it's handed in, the poster has one day to approve it or send it back with a note. If they do neither, the worker is paid automatically. A job can be sent back ${MAX_REJECTS} times; after that it's approve or let the day run out.</p>
    </div></details>`;
  $$('[data-tab]', root).forEach(b => b.onclick = () => { jobsTab = b.dataset.tab; renderJobs(); });
  fillJobs();
}
async function fillJobs() {
  const body = $('#jobs-body');
  if (!body) return;
  if (!status) return void (body.innerHTML = `<div class="empty"><b>No node connected.</b><button class="btn sm" data-node type="button">Connect to a node</button></div>`);
  if (jobsTab === 'post') {
    if (body.dataset.shown === 'post') return;   // keep what's typed
    body.dataset.shown = 'post';
    const g = gate('post a job');
    body.innerHTML = g || `<form class="panel heavy" id="job-form" autocomplete="off">
      <label class="f" for="j-title">What needs doing<input id="j-title" required maxlength="120" placeholder="Design a logo for my food truck"></label>
      <div class="two">
        <label class="f" for="j-pay">Pay (CDR)<input id="j-pay" required inputmode="decimal" placeholder="10"></label>
        <label class="f" for="j-open">Stays open for (days)<input id="j-open" required inputmode="decimal" value="7"></label>
      </div>
      <label class="f" for="j-work">Time to do it once taken (days)<input id="j-work" required inputmode="decimal" value="3" style="max-width:50%"></label>
      <div id="j-msg"></div><div><button class="btn hi" type="submit">Post job</button></div></form>`;
    $('#job-form')?.addEventListener('submit', async e => {
      e.preventDefault();
      const bad = t => { $('#j-msg').innerHTML = `<div class="msg bad">${t}</div>`; };
      const title = $('#j-title').value.trim(), pay = parseCndr($('#j-pay').value), ob = days($('#j-open').value), wb = days($('#j-work').value);
      if (!title) return bad('Describe the job.');
      if (!pay) return bad('Enter the pay in CDR, like 10 or 2.5.');
      if (!ob || ob < 10 || ob > 90 * DAY || !wb || wb < 10 || wb > 90 * DAY) return bad('Times can be from about 10 minutes (0.01 days) up to 90 days.');
      await loadMe();
      if (!card.me || pay > card.me.available) return bad(`You have ${fmtCndr(card.me?.available || 0)} CDR available to lock as pay.`);
      const id = await act(`Post “${esc(title)}” and lock <b>${fmtCndr(pay)} CDR</b> as its pay. You get it back if nobody does the job.`,
        { type: 'job_post', title, pay, open_blocks: ob, work_blocks: wb });
      if (id) { jobsTab = 'mine'; renderJobs(); }
    });
    return;
  }
  body.dataset.shown = jobsTab;
  try {
    if (jobsTab === 'open') {
      const jobs = await api('/api/jobs?status=open');
      body.innerHTML = jobs.length ? `<div class="list">${jobs.map(j => jobItem(j, 'open')).join('')}</div>`
        : `<div class="empty"><b>No open jobs right now.</b><span class="muted">Got something that needs doing? Post it and set the pay.</span><button class="btn sm" type="button" data-goto="post">Post a job</button></div>`;
    } else {
      const g = gate('see your jobs');
      if (g) return void (body.innerHTML = g);
      await loadMe();
      const jobs = card.me?.jobs || [];
      body.innerHTML = jobs.length ? `<div class="list">${jobs.map(j => jobItem(j, 'mine')).join('')}</div>`
        : `<div class="empty"><b>You haven't posted or taken any jobs.</b><button class="btn sm" type="button" data-goto="open">Browse open jobs</button></div>`;
    }
  } catch (err) { body.innerHTML = `<div class="msg bad">Couldn't load jobs: ${esc(err.message)}</div>`; }
  $$('[data-goto]', body).forEach(b => b.onclick = () => { jobsTab = b.dataset.goto; renderJobs(); });
  $$('[data-job]', body).forEach(b => b.onclick = () => jobAction(b.dataset.act, b.dataset.job, b));
}
function jobItem(j, mode) {
  const me = card.saved?.id;
  const mine = j.poster === me, working = j.worker === me;
  let meta = mine ? [] : [`Posted by ${idLink(j.poster)}`];
  let acts = '';
  if (j.status === 'open') {
    meta.push(`Closes ${until(j.open_until)}`, `${span(j.work_blocks)} to do it`);
    if (mine) acts = `<button class="btn ghost sm" data-act="cancel" data-job="${j.id}">Cancel and get pay back</button>`;
    else acts = `<button class="btn hi sm" data-act="take" data-job="${j.id}">Take this job</button>`;
  } else if (j.status === 'taken') {
    meta.push(`Worker ${idLink(j.worker)}`, `Due ${until(j.deadline)}`);
    if (j.note && j.rejects) meta.push(`Sent back: “${esc(j.note)}”`);
    if (working) acts = `<input placeholder="Note for the poster (link, details)" maxlength="200" data-note="${j.id}"><button class="btn hi sm" data-act="submit" data-job="${j.id}">Hand in</button>`;
    if (mine) acts = `<button class="btn ghost sm" data-act="approve" data-job="${j.id}">Approve and pay now</button>`;
  } else if (j.status === 'submitted') {
    meta.push(`Worker ${idLink(j.worker)}`, `Handed in: “${esc(j.note) || 'no note'}”`, `Pays itself ${until(j.review_until)}`);
    if (mine) acts = `<button class="btn hi sm" data-act="approve" data-job="${j.id}">Approve and pay</button>` +
      (j.rejects < MAX_REJECTS ? `<input placeholder="What needs fixing" maxlength="200" data-note="${j.id}"><button class="btn ghost sm" data-act="reject" data-job="${j.id}">Send back</button>` : `<span class="muted">Sent back ${MAX_REJECTS} times already.</span>`);
  } else if (j.worker) meta.push(`Worker ${idLink(j.worker)}`);
  const role = mode === 'mine' ? (mine ? 'You posted' : 'You\'re working') + ' · ' : '';
  return `<div class="item"><div style="display:grid;gap:6px;min-width:0"><h4><a href="#job-${j.id}" style="text-decoration:none">${esc(j.title)}</a></h4>
      <div class="meta"><span>${role}${chip(JOB_CHIP, j.status)}</span>${meta.map(m => `<span>${m}</span>`).join('')}</div></div>
    <div class="pay">${fmtCndr(j.pay, 4)}<small> CDR</small></div>
    ${acts && card.unlocked ? `<div class="acts">${acts}</div>` : acts && mode === 'open' && !mine ? `<div class="acts"><a class="btn sm" href="#wallet">${hasWallet() ? 'Unlock to take it' : 'Get a card to take it'}</a></div>` : ''}</div>`;
}
async function jobAction(kind, jid, btn) {
  const note = $(`[data-note="${jid}"]`)?.value?.trim() ?? '';
  const job = await api('/api/job/' + jid).catch(() => null);
  if (!job) return;
  const text = {
    take: `Take “${esc(job.title)}”. You'll have ${span(job.work_blocks)} to hand it in, or the pay goes back to the poster.`,
    cancel: `Cancel “${esc(job.title)}” and get ${fmtCndr(job.pay)} CDR back.`,
    submit: `Hand in “${esc(job.title)}”. The poster has a day to approve it or send it back; if they do neither, you're paid.`,
    approve: `Approve and pay <b>${fmtCndr(job.pay)} CDR</b> to ${formatId(job.worker || '')}.`,
    reject: `Send “${esc(job.title)}” back with your note. The worker gets another ${span(job.work_blocks)}.`,
  }[kind];
  if (kind === 'reject' && !note) { btn.insertAdjacentHTML('afterend', `<span class="out" style="font-size:14px">Say what needs fixing first.</span>`); return; }
  const fields = { take: { type: 'job_take', job: jid }, cancel: { type: 'job_cancel', job: jid },
    submit: { type: 'job_submit', job: jid, note }, approve: { type: 'job_approve', job: jid },
    reject: { type: 'job_reject', job: jid, note } }[kind];
  const ok = await act(text, fields);
  if (ok) { if (kind === 'take') jobsTab = 'mine'; renderJobs(); }
}

// ------------------------------------------------------------------ loans page
const LOAN_CHIP = { open: ['go', 'Looking for a lender'], active: ['dim', 'Active'], overdue: ['warn', 'Overdue'],
  paid: ['ok', 'Paid off'], cancelled: ['dim', 'Withdrawn'] };
const pct = l => l.amount ? ((l.repay - l.amount) / l.amount * 100) : 0;
let loansTab = 'open';
function renderLoans() {
  const root = $('#loans-root');
  root.innerHTML = `<div class="page-head"><h2>Loans.</h2>
    <p class="lede">Borrow CDR to get something started, or back someone who's starting something. Loans are between people. The network keeps the record and does the collecting.</p></div>
    <div class="tabs" role="tablist">
      <button role="tab" data-tab="open" aria-selected="${loansTab === 'open'}">Requests</button>
      <button role="tab" data-tab="mine" aria-selected="${loansTab === 'mine'}">My loans</button>
      <button role="tab" data-tab="ask" aria-selected="${loansTab === 'ask'}">Ask for a loan</button>
    </div>
    <div id="loans-body"></div>
    <details><summary><b>How loans work</b></summary><div style="display:grid;gap:8px;margin-top:10px;max-width:65ch">
      <p>The borrower sets the amount, the total to pay back and how long. Anyone can fund the request; the CDR goes to the borrower right away.</p>
      <p>Either side can close a loan early. Whatever the borrower has goes straight back to the lender, up to what's owed, and the interest is dropped. Anything still owed is collected the same way as a late loan.</p>
      <p>On the due date the network collects what it can from the borrower's balance. After that, half of every payment the borrower receives goes to the lender until it's paid off.</p>
      <p class="muted">Lending is between the people involved. Nobody guarantees a loan gets repaid.</p>
    </div></details>`;
  $$('[data-tab]', root).forEach(b => b.onclick = () => { loansTab = b.dataset.tab; renderLoans(); });
  fillLoans();
}
async function fillLoans() {
  const body = $('#loans-body');
  if (!body) return;
  if (!status) return void (body.innerHTML = `<div class="empty"><b>No node connected.</b><button class="btn sm" data-node type="button">Connect to a node</button></div>`);
  if (loansTab === 'ask') {
    if (body.dataset.shown === 'ask') return;
    body.dataset.shown = 'ask';
    const g = gate('ask for a loan');
    body.innerHTML = g || `<form class="panel heavy" id="loan-form" autocomplete="off">
      <label class="f" for="l-memo">What it's for<input id="l-memo" required maxlength="120" placeholder="Stock for my sneaker resale shop"></label>
      <div class="two">
        <label class="f" for="l-amt">Borrow (CDR)<input id="l-amt" required inputmode="decimal" placeholder="20"></label>
        <label class="f" for="l-repay">Pay back in total (CDR)<input id="l-repay" required inputmode="decimal" placeholder="22"></label>
      </div>
      <label class="f" for="l-days">Pay back within (days)<input id="l-days" required inputmode="decimal" value="30" style="max-width:50%"></label>
      <p class="muted" id="l-sum"></p>
      <div id="l-msg"></div><div><button class="btn hi" type="submit">Post request</button></div></form>`;
    const sum = () => {
      const a = parseCndr($('#l-amt').value), r = parseCndr($('#l-repay').value);
      $('#l-sum').textContent = a && r && r >= a ? `That's ${((r - a) / a * 100).toFixed(1)}% interest: you'd pay ${fmtCndr(r - a)} CDR on top.` : '';
    };
    ['#l-amt', '#l-repay'].forEach(s => $(s)?.addEventListener('input', sum));
    $('#loan-form')?.addEventListener('submit', async e => {
      e.preventDefault();
      const bad = t => { $('#l-msg').innerHTML = `<div class="msg bad">${t}</div>`; };
      const memo = $('#l-memo').value.trim(), amount = parseCndr($('#l-amt').value), repay = parseCndr($('#l-repay').value), term = days($('#l-days').value);
      if (!memo) return bad("Say what it's for. Lenders decide based on this.");
      if (!amount || !repay) return bad('Enter both amounts in CDR.');
      if (repay < amount) return bad("The total to pay back can't be less than what you borrow.");
      if (!term || term < 10 || term > 2 * COOLING) return bad('Pick a term between about 10 minutes and 2 years.');
      const id = await act(`Ask for <b>${fmtCndr(amount)} CDR</b>, paying back <b>${fmtCndr(repay)}</b> within ${span(term)}.`,
        { type: 'loan_request', amount, repay, term, memo });
      if (id) { loansTab = 'mine'; renderLoans(); }
    });
    return;
  }
  body.dataset.shown = loansTab;
  try {
    let loans;
    if (loansTab === 'open') loans = await api('/api/loans?status=open');
    else {
      const g = gate('see your loans');
      if (g) return void (body.innerHTML = g);
      await loadMe(); loans = card.me?.loans || [];
    }
    body.innerHTML = loans.length ? `<div class="list">${loans.map(l => loanItem(l, loansTab)).join('')}</div>`
      : loansTab === 'open' ? `<div class="empty"><b>No loan requests right now.</b><button class="btn sm" type="button" data-goto="ask">Ask for a loan</button></div>`
      : `<div class="empty"><b>No loans yet.</b><button class="btn sm" type="button" data-goto="open">See requests</button></div>`;
  } catch (err) { body.innerHTML = `<div class="msg bad">Couldn't load loans: ${esc(err.message)}</div>`; }
  $$('[data-goto]', body).forEach(b => b.onclick = () => { loansTab = b.dataset.goto; renderLoans(); });
  $$('[data-loan]', body).forEach(b => b.onclick = () => loanAction(b.dataset.act, b.dataset.loan));
}
function loanItem(l, mode) {
  const me = card.saved?.id, borrower = l.borrower === me, lender = l.lender === me;
  const meta = [`${mode === 'mine' ? (borrower ? 'You borrowed' : 'You lent') + ' · ' : ''}${chip(LOAN_CHIP, l.status)}`];
  if (mode !== 'mine' || lender) meta.push(`Borrower ${idLink(l.borrower)}`);
  if (borrower && l.lender) meta.push(`Lender ${idLink(l.lender)}`);
  meta.push(`Pays back ${fmtCndr(l.repay, 4)} (${pct(l).toFixed(1)}%)`);
  if (l.status === 'open') meta.push(`Within ${span(l.term)}`);
  if (l.status === 'active') meta.push(`Due ${until(l.due)}`, `Owed ${fmtCndr(l.owed, 4)}`);
  if (l.status === 'overdue') meta.push(`Still owed ${fmtCndr(l.owed, 4)}`, 'Half of incoming payments go to the lender');
  let acts = '';
  if (card.unlocked) {
    if (l.status === 'open' && borrower) acts = `<button class="btn ghost sm" data-act="cancel" data-loan="${l.id}">Withdraw request</button>`;
    if (l.status === 'open' && !borrower) acts = `<button class="btn hi sm" data-act="fund" data-loan="${l.id}">Fund this loan</button>`;
    if (['active', 'overdue'].includes(l.status) && borrower) acts = `<input placeholder="Amount (CDR)" inputmode="decimal" data-amt="${l.id}"><button class="btn hi sm" data-act="repay" data-loan="${l.id}">Repay</button><button class="btn ghost sm" data-act="close" data-loan="${l.id}">Close and return what I have</button>`;
    if (['active', 'overdue'].includes(l.status) && lender) acts = `<button class="btn ghost sm" data-act="close" data-loan="${l.id}">Call it back</button>`;
  } else if (l.status === 'open' && mode === 'open') acts = `<a class="btn sm" href="#wallet">${hasWallet() ? 'Unlock to fund it' : 'Get a card to fund it'}</a>`;
  return `<div class="item"><div style="display:grid;gap:6px;min-width:0"><h4><a href="#loan-${l.id}" style="text-decoration:none">${esc(l.memo)}</a></h4>
    <div class="meta">${meta.map(m => `<span>${m}</span>`).join('')}</div></div>
    <div class="pay">${fmtCndr(l.amount, 4)}<small> CDR</small></div>${acts ? `<div class="acts">${acts}</div>` : ''}</div>`;
}
async function loanAction(kind, lid) {
  const l = await api('/api/loan/' + lid).catch(() => null);
  if (!l) return;
  const early = status && status.height + 1 < l.due;
  let fields = { type: 'loan_' + kind, loan: lid }, text;
  if (kind === 'fund') text = `Send <b>${fmtCndr(l.amount)} CDR</b> to ${formatId(l.borrower)} now. They owe you <b>${fmtCndr(l.repay)}</b> within ${span(l.term)}. You can call it back any time.`;
  if (kind === 'cancel') text = 'Withdraw this loan request.';
  if (kind === 'close') {
    const owe = early ? Math.max(0, l.amount - l.paid) : l.owed;
    text = l.borrower === card.saved.id
      ? `Close this loan. Up to <b>${fmtCndr(owe)} CDR</b> goes back to the lender from your balance now${early ? ' (interest dropped because it\'s early)' : ''}. Anything left is taken from future payments you receive.`
      : `Call this loan back. Up to <b>${fmtCndr(owe)} CDR</b> comes back from the borrower's balance now${early ? ' (interest dropped because it\'s early)' : ''}; the rest is collected from their future income.`;
  }
  if (kind === 'repay') {
    const amount = parseCndr($(`[data-amt="${lid}"]`)?.value || '');
    if (!amount || amount > l.owed) { $(`[data-amt="${lid}"]`)?.focus(); return; }
    fields.amount = amount;
    text = `Repay <b>${fmtCndr(amount)} CDR</b> of the ${fmtCndr(l.owed)} owed.`;
  }
  const ok = await act(text, fields);
  if (ok) { if (kind === 'fund') loansTab = 'mine'; renderLoans(); }
}

// ------------------------------------------------------------------ explorer
async function renderExplorer(m) {
  const root = $('#explore-root');
  if (!status) return void (root.innerHTML = `<div class="empty"><b>No node connected.</b><span class="muted">The explorer reads straight from a Cinder node.</span><button class="btn sm" data-node type="button">Connect to a node</button></div>`);
  root.innerHTML = `<p class="muted">Loading…</p>`;
  try {
    if (!m) root.innerHTML = await latestView();
    else if (m[1] === 'block') root.innerHTML = blockView(await api('/api/block/' + m[2]));
    else if (m[1] === 'tx') root.innerHTML = txView(await api('/api/tx/' + m[2]));
    else if (m[1] === 'id') root.innerHTML = idView(await api('/api/id/' + m[2]));
    else if (m[1] === 'job') root.innerHTML = jobView(await api('/api/job/' + m[2]));
    else if (m[1] === 'loan') root.innerHTML = loanView(await api('/api/loan/' + m[2]));
  } catch (e) {
    root.innerHTML = `<div class="empty"><b>Nothing found.</b><span class="muted">${esc(e.message === 'not found' ? 'Nothing on this node matches that.' : e.message)}</span><a class="btn sm" href="#explorer">Back to latest blocks</a></div>`;
  }
}
function txLine(t) {
  const amt = t.amount ?? t.pay;
  return `<tr data-href="#tx-${t.txid}"><td>${ACTION[t.type] || t.type}</td><td>${idLink(t.from)}</td><td>${t.to ? idLink(t.to) : ''}</td><td class="num">${amt ? fmtCndr(amt, 4) : ''}</td></tr>`;
}
async function latestView() {
  const [blocks, mem] = await Promise.all([api('/api/blocks?limit=25'), api('/api/mempool')]);
  return `<div style="display:grid;gap:28px">
    <div class="panel"><h3>Waiting for the next block</h3>${mem.length ? `<div class="scroll"><table class="ledger"><thead><tr><th>What</th><th>From</th><th>To</th><th class="num">CDR</th></tr></thead><tbody>${mem.map(txLine).join('')}</tbody></table></div>` : `<p class="muted">Nothing waiting. Everything sent so far is in a block.</p>`}</div>
    <div><h3 style="margin-bottom:12px">Latest blocks</h3><div class="scroll"><table class="ledger"><thead><tr><th>Block</th><th>When</th><th>Mined by</th><th class="num">Transactions</th><th class="num">Reward</th></tr></thead><tbody>${
      blocks.map(b => `<tr data-href="#block-${b.height}"><td><b>${b.height}</b></td><td>${ago(b.time)}</td><td>${b.miner ? `<span class="mono-id">${formatId(b.miner)}</span>` : 'genesis'}</td><td class="num">${b.tx_count}</td><td class="num">${fmtCndr(b.reward + b.fees, 4)}</td></tr>`).join('')
    }</tbody></table></div></div></div>`;
}
function blockView(b) {
  return `<div style="display:grid;gap:24px">
    <div class="row" style="justify-content:space-between"><h2>Block ${b.height}</h2>
      <div class="row">${b.height > 0 ? `<a class="btn ghost sm" href="#block-${b.height - 1}">Older</a>` : ''}${b.next ? `<a class="btn ghost sm" href="#block-${b.height + 1}">Newer</a>` : ''}</div></div>
    <dl class="kv">
      <dt>Hash</dt><dd class="mono">${b.hash}</dd>
      <dt>Status</dt><dd>${b.main ? `${b.confirmations.toLocaleString()} confirmation${b.confirmations === 1 ? '' : 's'}` : 'Off the main chain (lost a race)'}</dd>
      <dt>Time</dt><dd>${new Date(b.time * 1000).toLocaleString()} <span class="muted">(${ago(b.time)})</span></dd>
      <dt>Mined by</dt><dd>${b.height ? idLink(b.miner) : '<span class="muted">nobody, the first block pays no one</span>'}</dd>
      <dt>Reward</dt><dd>${fmtCndr(b.reward)} CDR${b.fees ? ` + ${fmtCndr(b.fees)} in fees` : ''}</dd>
      <dt>Difficulty</dt><dd>${Number(b.difficulty).toLocaleString()} hashes expected</dd>
      ${b.note ? `<dt>Note</dt><dd>${esc(b.note)}</dd>` : ''}
    </dl>
    <div><h3 style="margin-bottom:12px">${b.tx_count} transaction${b.tx_count === 1 ? '' : 's'}</h3>${b.txs.length ? `<div class="scroll"><table class="ledger"><thead><tr><th>What</th><th>From</th><th>To</th><th class="num">CDR</th></tr></thead><tbody>${b.txs.map(txLine).join('')}</tbody></table></div>` : `<p class="muted">An empty block. The miner was paid the reward only.</p>`}</div>
    ${b.effects.filter(e => !['payment', 'mined'].includes(e.why)).length ? `<div><h3 style="margin-bottom:12px">Automatic moves</h3><p class="muted" style="margin-bottom:10px">Things the network did on its own in this block: deadlines, refunds, loan collection.</p><div class="scroll"><table class="ledger"><tbody>${
      b.effects.filter(e => !['payment', 'mined', 'fee'].includes(e.why)).map(e => `<tr><td>${esc(e.why)}</td><td>${e.from ? idLink(e.from) : ''}</td><td>${e.to ? idLink(e.to) : 'held'}</td><td class="num">${fmtCndr(e.amount, 4)}</td></tr>`).join('')}</tbody></table></div></div>` : ''}</div>`;
}
function txView(t) {
  const rows = [['Receipt', `<span class="mono">${t.txid}</span>`],
    ['Status', t.block ? `In block <a href="#block-${t.height}">${t.height}</a>, ${t.confirmations.toLocaleString()} confirmation${t.confirmations === 1 ? '' : 's'}` : '<span class="chip go">Waiting for the next block</span>'],
    ['What', ACTION[t.type] || t.type], ['From', idLink(t.from)]];
  if (t.to) rows.push(['To', idLink(t.to)]);
  if (t.amount) rows.push(['Amount', `<b>${fmtCndr(t.amount)} CDR</b>`]);
  if (t.pay) rows.push(['Pay', `${fmtCndr(t.pay)} CDR`]);
  if (t.title) rows.push(['Job', esc(t.title)]);
  if (t.repay) rows.push(['Pays back', `${fmtCndr(t.repay)} CDR within ${span(t.term)}`]);
  if (t.job) rows.push(['Job', `<a href="#job-${t.job}">View job</a>`]);
  if (t.loan) rows.push(['Loan', `<a href="#loan-${t.loan}">View loan</a>`]);
  if (t.type === 'job_post') rows.push(['Job', `<a href="#job-${t.txid}">View job</a>`]);
  if (t.type === 'loan_request') rows.push(['Loan', `<a href="#loan-${t.txid}">View loan</a>`]);
  if (t.memo) rows.push(['Note', esc(t.memo)]);
  if (t.note) rows.push(['Note', esc(t.note)]);
  rows.push(['Fee', t.fee ? `${fmtCndr(t.fee)} CDR` : 'None (paid with a second of work)']);
  if (t.time) rows.push(['Time', new Date(t.time * 1000).toLocaleString()]);
  return `<div style="display:grid;gap:24px"><h2>Receipt</h2><dl class="kv">${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('')}</dl></div>`;
}
function idView(a) {
  return `<div style="display:grid;gap:24px"><div class="cols" style="align-items:center">${cardFront(a.id, a.registered ? 'On the network' : 'Not registered')}
    <div style="display:grid;gap:8px"><p class="muted">Balance</p><div class="balance">${fmtCndr(a.balance, 4)}<small>CDR</small></div>
    ${a.owes.length ? `<p class="out">Owes ${fmtCndr(a.owes.reduce((n, l) => n + l.owed, 0))} CDR on ${a.owes.length} loan${a.owes.length > 1 ? 's' : ''}</p>` : ''}</div></div>
    <div class="scroll">${activity(a, a.id)}</div></div>`;
}
function jobView(j) {
  return `<div style="display:grid;gap:24px"><h2>${esc(j.title)}</h2><dl class="kv">
    <dt>Status</dt><dd>${chip(JOB_CHIP, j.status)}</dd><dt>Pay</dt><dd><b>${fmtCndr(j.pay)} CDR</b></dd>
    <dt>Posted by</dt><dd>${idLink(j.poster)}</dd><dt>Worker</dt><dd>${idLink(j.worker)}</dd>
    ${j.status === 'open' ? `<dt>Closes</dt><dd>${until(j.open_until)}</dd>` : ''}
    ${j.status === 'taken' ? `<dt>Due</dt><dd>${until(j.deadline)}</dd>` : ''}
    ${j.status === 'submitted' ? `<dt>Pays itself</dt><dd>${until(j.review_until)} unless the poster sends it back</dd>` : ''}
    <dt>Time to do it</dt><dd>${span(j.work_blocks)}</dd><dt>Sent back</dt><dd>${j.rejects} of ${MAX_REJECTS} times</dd>
    ${j.note ? `<dt>Latest note</dt><dd>${esc(j.note)}</dd>` : ''}</dl><div><a class="btn sm" href="#jobs">Go to jobs</a></div></div>`;
}
function loanView(l) {
  return `<div style="display:grid;gap:24px"><h2>${esc(l.memo)}</h2><dl class="kv">
    <dt>Status</dt><dd>${chip(LOAN_CHIP, l.status)}</dd><dt>Borrowed</dt><dd><b>${fmtCndr(l.amount)} CDR</b></dd>
    <dt>Pays back</dt><dd>${fmtCndr(l.repay)} CDR (${pct(l).toFixed(1)}%) within ${span(l.term)}</dd>
    <dt>Borrower</dt><dd>${idLink(l.borrower)}</dd><dt>Lender</dt><dd>${idLink(l.lender)}</dd>
    ${l.due ? `<dt>Due</dt><dd>${l.due > (status?.height ?? 0) ? until(l.due) : `block ${l.due}`}</dd>` : ''}
    <dt>Repaid so far</dt><dd>${fmtCndr(l.paid)} CDR</dd>${['active', 'overdue'].includes(l.status) ? `<dt>Still owed</dt><dd>${fmtCndr(l.owed)} CDR</dd>` : ''}
    </dl><div><a class="btn sm" href="#loans">Go to loans</a></div></div>`;
}
$('#search').onsubmit = async e => {
  e.preventDefault();
  const q = $('#q').value.trim();
  if (!q) return;
  if (/^\d{1,9}$/.test(q)) location.hash = 'block-' + q;
  else if (isValidId(normalizeId(q))) location.hash = 'id-' + normalizeId(q);
  else if (/^[0-9a-f]{64}$/i.test(q)) {
    const h = q.toLowerCase();
    for (const [kind, path] of [['block', 'block'], ['tx', 'tx'], ['job', 'job'], ['loan', 'loan']]) {
      try { await api(`/api/${path}/${h}`); location.hash = `${kind}-${h}`; return; } catch {}
    }
    $('#explore-root').innerHTML = `<div class="msg bad">Nothing on this node has that number.</div>`;
  } else if (normalizeId(q).length === 16) $('#explore-root').innerHTML = `<div class="msg bad">That looks like a card ID with a typo in it. Check each character.</div>`;
  else $('#explore-root').innerHTML = `<div class="msg bad">Search takes a card ID, a block height, or a 64-character block hash or receipt number.</div>`;
};

// ------------------------------------------------------------------ paying a shop (customer side)
const isAndroid = /Android/i.test(navigator.userAgent);
function appPayLink(it) {
  const back = `${location.origin}${location.pathname}#pay-${it.to}-${it.amount}-${it.ref}-web`;
  return `intent://pay?to=${it.to}&amount=${it.amount}&ref=${it.ref}#Intent;scheme=cinderpay;package=app.cinder.wallet;` +
    `S.browser_fallback_url=${encodeURIComponent(back)};end`;
}
// A checkout QR opens #pay-<shop ID>-<ash>-<order code>. The wallet shows
// the request at the top; paying sends it with the note "Order <code>".
// iPhones open links from NFC taps in Safari, never in a Home Screen app, and the two don't share
// data. So the Home Screen app can pick a request up from the clipboard.
const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const standalone = navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;
function parsePayRequest(text) {
  const m = String(text || '').match(/#?pay-([0-9A-Z]{16})-(\d{1,16})-([0-9A-Z]{6})/);
  return m && isValidId(m[1]) && Number(m[2]) > 0 ? { to: m[1], amount: Number(m[2]), ref: m[3] } : null;
}
function paintIntent() {
  const slot = $('#w-intent'), it = card.intent;
  if (!slot) return;
  if (!it) {
    slot.innerHTML = standalone && isIOS ? `<div class="msg" style="display:grid;gap:10px;margin-bottom:12px">
      <span>Paying a shop or a phone that tapped you? If it opened in Safari, tap Copy request there, then paste it here.</span>
      <div class="row"><button class="btn ghost sm" type="button" id="i-paste">Paste payment request</button></div><div id="i-pmsg"></div></div>` : '';
    const b = $('#i-paste');
    if (b) b.onclick = async () => {
      try {
        const req = parsePayRequest(await navigator.clipboard.readText());
        if (!req) { $('#i-pmsg').innerHTML = '<p class="muted">There isn’t a Cinder payment request on the clipboard.</p>'; return; }
        card.intent = req; paintIntent();
      } catch { $('#i-pmsg').innerHTML = '<p class="muted">Couldn’t read the clipboard. Allow paste when iOS asks.</p>'; }
    };
    return;
  }
  slot.innerHTML = `${isAndroid ? `<div class="msg" style="display:grid;gap:10px;margin-bottom:12px">
      <span>Paying from your phone? The Cinder Wallet app pays with your fingerprint.</span>
      <div class="row"><a class="btn hi sm" href="${esc(appPayLink(it))}">Pay with Cinder Wallet</a><a class="btn ghost sm" href="phone/#wallet">Get the app</a></div></div>` : ''}
    <div class="intent">
      <div style="display:grid;gap:6px;min-width:0">
        <p>Pay the shop</p>
        <div class="co-amount sm">${fmtCndr(it.amount)}<small>CDR</small></div>
        <p>To <span class="mono-id">${formatId(it.to)}</span>, order ${esc(it.ref)}</p>
      </div>
      <div class="row"><button class="btn" type="button" id="i-pay">Pay ${fmtCndr(it.amount)} CDR</button><button class="btn ghost sm" type="button" id="i-no">Not now</button></div>
      <div id="i-msg" style="grid-column:1/-1"></div>
    </div>${isIOS && !standalone ? `<div class="msg" style="display:grid;gap:10px;margin-top:12px">
      <span>Use the Cinder app on your Home Screen instead? Copy this request, open the app, and tap Paste payment request.</span>
      <div class="row"><button class="btn ghost sm" type="button" id="i-copy">Copy request</button></div></div>` : ''}`;
  const cp = $('#i-copy');
  if (cp) cp.onclick = async () => {
    const link = `${location.origin}${location.pathname}#pay-${it.to}-${it.amount}-${it.ref}`;
    try { await navigator.clipboard.writeText(link); cp.textContent = 'Copied. Open the Cinder app'; } catch { cp.textContent = 'Couldn’t copy'; }
  };
  $('#i-no').onclick = () => { card.intent = null; history.replaceState(null, '', '#wallet'); paintIntent(); };
  $('#i-pay').onclick = async () => {
    const bad = t => { $('#i-msg').innerHTML = `<div class="msg bad">${t}</div>`; };
    await loadMe();
    if (!card.me) return bad("Can't reach a node to check your balance.");
    if (!card.me.registered) return bad('Your card isn’t on the network yet. Wait for the next block, then try again.');
    if (it.amount > card.me.available) return bad(`You have ${fmtCndr(card.me.available)} CDR, which isn't enough.`);
    let shop;
    try { shop = await api('/api/id/' + it.to); } catch (err) { return bad(esc(err.message)); }
    if (!shop.registered) return bad("That shop's card isn't on the network. Ask the shop to check its card ID.");
    const txid = await act(`Pay <b>${fmtCndr(it.amount)} CDR</b> to <span class="mono-id">${formatId(it.to)}</span> for order ${esc(it.ref)}. Payments can't be reversed.`,
      { type: 'pay', to: it.to, amount: it.amount, memo: `Order ${it.ref}` });
    if (!txid) return;
    card.intent = null;
    history.replaceState(null, '', '#wallet');
    slot.innerHTML = `<div class="msg good" style="display:grid;gap:6px"><b>Paid ${fmtCndr(it.amount)} CDR for order ${esc(it.ref)}.</b>
      The shop's screen turns green when the next block lands, usually within a minute. <a href="#tx-${txid}">Receipt</a></div>`;
    refreshWallet();
  };
}

// ------------------------------------------------------------------ checkout (shop side)
// Watches the shop's card for a payment with this order's code. It never
// needs the shop's keys, so it's safe on a shared tablet at the counter.
const co = { shop: store.get('shop'), amount: 0, ref: '', desc: '', started: 0, state: 'setup', timer: null, from: null, txid: null };
function stopWatching() { clearInterval(co.timer); co.timer = null; }
function newRef() { return [...crypto.getRandomValues(new Uint8Array(6))].map(x => X.ID_ALPHABET[x & 31]).join(''); }
const payLink = () => `${location.origin}${location.pathname}#pay-${co.shop}-${co.amount}-${co.ref}`;
function qrSvg(text) {
  const q = qrcode(0, 'M'); q.addData(text); q.make();
  const n = q.getModuleCount(), pad = 4;
  let d = '';
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (q.isDark(r, c)) d += `M${c + pad} ${r + pad}h1v1h-1z`;
  return `<svg viewBox="0 0 ${n + 2 * pad} ${n + 2 * pad}" role="img" aria-label="QR code that opens the payment in a Cinder wallet" shape-rendering="crispEdges">
    <rect width="100%" height="100%" fill="var(--qr-bg)"/><path d="${d}" fill="var(--qr-ink)"/></svg>`;
}

function renderCheckout() {
  const root = $('#checkout-root');
  stopWatching();
  if (co.state === 'setup') {
    const shop = co.shop || card.saved?.id || '';
    root.innerHTML = `<div class="page-head"><h2>Checkout.</h2>
      <p class="lede">Take CDR at the counter. Enter the price, turn the screen to the customer, and it goes green when their payment arrives.</p></div>
      <form class="panel heavy" id="co-form" autocomplete="off" style="max-width:680px">
        <label class="f" for="co-shop">Shop's card ID <span class="hint">Payments go to this card. This screen only watches it and can't spend from it.</span>
          <input id="co-shop" class="mono" required placeholder="XXXX XXXX XXXX XXXX" value="${esc(formatId(shop))}" spellcheck="false" autocapitalize="characters"></label>
        <div class="two">
          <label class="f" for="co-amt">Price (CDR)<input id="co-amt" required inputmode="decimal" placeholder="2.5"></label>
          <label class="f" for="co-desc">What for <span class="hint">Optional, only shown on this screen.</span><input id="co-desc" maxlength="60" placeholder="2 tacos"></label>
        </div>
        <div id="co-msg"></div>
        <div><button class="btn hi" type="submit">Show to customer</button></div>
      </form>
      <details><summary><b>How checkout works</b></summary><div style="display:grid;gap:8px;margin-top:10px;max-width:65ch">
        <p>Each checkout gets a 6-character order code. The customer scans the QR code, which opens their Cinder wallet with the price, your card ID and the code filled in. They confirm with their SPP.</p>
        <p>This screen shows "Payment on its way" as soon as the payment reaches the network, and "Paid" once it's in a block, usually within a minute.</p>
        <p>No camera? The customer can send the price to your card ID with the note "Order" and the code, and it's matched the same way.</p>
      </div></details>`;
    if (co.err) $('#co-msg').innerHTML = `<div class="msg bad">${esc(co.err)}</div>`;
    co.err = null;
    $('#co-form').onsubmit = async e => {
      e.preventDefault();
      const bad = t => { $('#co-msg').innerHTML = `<div class="msg bad">${t}</div>`; };
      const id = normalizeId($('#co-shop').value), amount = parseCndr($('#co-amt').value);
      if (!isValidId(id)) return bad("That card ID isn't right. It's 16 letters and numbers, and one looks mistyped.");
      if (!amount) return bad('Enter the price in CDR, like 2.5.');
      if (!status) return bad("Can't reach a node. Connect to one with the button at the top right.");
      try {
        const me = await api('/api/id/' + id);
        if (!me.registered && !me.registering) return bad("That card isn't on the network yet. Make sure it's the shop's card.");
      } catch (err) { return bad(esc(err.message)); }
      store.set('shop', id);
      Object.assign(co, { shop: id, amount, ref: newRef(), desc: $('#co-desc').value.trim(),
        started: Math.floor(Date.now() / 1000), state: 'waiting', from: null, txid: null });
      renderCheckout();
    };
    return;
  }
  root.innerHTML = `<div class="checkout" data-state="${co.state}">
      <div class="co-main">
        <p class="muted">${co.desc ? esc(co.desc) : 'Amount due'}</p>
        <div class="co-amount">${fmtCndr(co.amount)}<small>CDR</small></div>
        <dl class="kv">
          <dt>Pay to</dt><dd class="mono-id">${formatId(co.shop)}</dd>
          <dt>Order</dt><dd class="mono-id">${co.ref}</dd>
        </dl>
        <div id="co-status"></div>
      </div>
      <figure class="qr">${qrSvg(payLink())}
        <figcaption>Scan with your phone camera to pay from your Cinder wallet.</figcaption></figure>
    </div>
    <div class="row">
      <button class="btn hi" type="button" id="co-next">New checkout</button>
      <button class="btn ghost sm" type="button" id="co-copy">Copy pay link</button>
      <button class="btn ghost sm" type="button" id="co-stop" ${co.state === 'paid' ? 'hidden' : ''}>Cancel this one</button>
    </div>`;
  $('#co-next').onclick = () => { co.state = 'setup'; renderCheckout(); };
  $('#co-stop').onclick = () => { co.state = 'setup'; renderCheckout(); };
  $('#co-copy').onclick = e => copy(payLink(), e.currentTarget);
  paintCoStatus();
  if (co.state !== 'paid') { co.timer = setInterval(checkPaid, 3000); checkPaid(); }
}

function paintCoStatus() {
  const el = $('#co-status');
  if (!el) return;
  $('.checkout').dataset.state = co.state;
  el.className = 'co-status ' + co.state;
  el.innerHTML = {
    waiting: 'Waiting for payment…',
    seen: 'Payment on its way. Confirming…',
    partial: `Only ${fmtCndr(co.got)} of ${fmtCndr(co.amount)} CDR received so far.`,
    paid: `<b>Paid</b><span>From <span class="mono-id">${formatId(co.from)}</span>.${co.txid ? ` <a href="#tx-${co.txid}">See receipt</a>` : ''}</span>`,
  }[co.state];
  const stop = $('#co-stop'); if (stop) stop.hidden = co.state === 'paid';
}

async function checkPaid() {
  if (current !== 'checkout' || co.state === 'setup' || co.state === 'paid') return stopWatching();
  let me;
  try { me = await api('/api/id/' + co.shop); } catch { return; }
  const tag = `Order ${co.ref}`;
  const ours = memo => typeof memo === 'string' && memo.startsWith(tag);
  const done = me.history.filter(e => e.kind === 'money' && e.why === 'payment' && e.to === co.shop && ours(e.memo));
  const waiting = me.pending.filter(t => t.type === 'pay' && t.to === co.shop && ours(t.memo));
  const sum = a => a.reduce((n, x) => n + x.amount, 0);
  const got = sum(done), coming = sum(waiting);
  const prev = co.state;
  if (got >= co.amount) Object.assign(co, { state: 'paid', from: done[0].from, txid: done[0].ref });
  else if (got + coming >= co.amount) co.state = 'seen';
  else if (got + coming > 0) Object.assign(co, { state: 'partial', got: got + coming });
  if (co.state !== prev) paintCoStatus();
  if (co.state === 'paid') stopWatching();
}

// ------------------------------------------------------------------ router, refresh
let current = 'home';
function route() {
  // A card sent from the Cinder Wallet app: #import=word.word… . Keep the words in memory only and
  // take them out of the address bar right away.
  const im = location.hash.match(/^#import=([a-z.]+)$/);
  if (im) {
    try { card.importing = X.entropyFromPhrase(im[1].split('.').join(' ')); } catch { card.importing = null; card.importError = true; }
    history.replaceState(null, '', location.pathname + location.search + '#wallet');
  }
  const h = location.hash.slice(1) || 'home';
  let view = h, arg = null;
  const m = h.match(/^(block|tx|id|job|loan)-(.+)$/);
  if (m) { view = 'explorer'; arg = m; }
  const pm = h.match(/^pay-([0-9A-Z]{16})-(\d{1,16})-([0-9A-Z]{6})(-web)?$/);
  if (pm && isValidId(pm[1]) && Number(pm[2]) > 0) {
    card.intent = { to: pm[1], amount: Number(pm[2]), ref: pm[3] }; view = 'wallet';
    // On Android, hand the payment to the Cinder Wallet app's pay sheet if it's installed.
    if (!pm[4] && isAndroid) { try { location.href = appPayLink(card.intent); } catch {} }
  }
  if (!['home', 'start', 'wallet', 'jobs', 'loans', 'checkout', 'explorer'].includes(view)) view = 'home';
  if (current === 'checkout' && view !== 'checkout') stopWatching();
  current = view;
  $$('[data-view]').forEach(el => { el.hidden = el.dataset.view !== view; });
  $$('.nav a, .tabbar a').forEach(a => a.getAttribute('href') === '#' + view ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current'));
  const moreOn = ['start', 'checkout', 'explorer'].includes(view);
  if ($('#more-btn')) { moreOn ? $('#more-btn').setAttribute('aria-current', 'page') : $('#more-btn').removeAttribute('aria-current'); }
  $$('#more a').forEach(a => a.getAttribute('href') === '#' + view ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current'));
  if ($('#more')) { $('#more').hidden = true; $('#more-btn').setAttribute('aria-expanded', 'false'); }
  if (view === 'home') { paintWall(); paintCurve(); }
  if (view === 'wallet') renderWallet();
  if (view === 'jobs') renderJobs();
  if (view === 'loans') renderLoans();
  if (view === 'checkout') renderCheckout();
  if (view === 'explorer') renderExplorer(arg);
  window.scrollTo(0, 0);
}
async function tick() {
  const before = status?.tip;
  try { status = await api('/api/status'); } catch {
    status = null;
    // The node phone's address can change; look it up again now and then.
    if (Date.now() - lastFind > 8_000) await findNode();
  }
  paintStatus();
  if (!status || status.tip === before) return;
  if (current === 'home') { paintWall(); paintCurve(); }
  if (card.unlocked) await loadMe();
  if (current === 'wallet' && card.unlocked) refreshWallet();
  if (current === 'jobs' && jobsTab !== 'post') fillJobs();
  if (current === 'loans' && loansTab !== 'ask') fillLoans();
}

document.addEventListener('click', e => {
  const mb = e.target.closest('#more-btn');
  if (mb) { const m = $('#more'); m.hidden = !m.hidden; mb.setAttribute('aria-expanded', String(!m.hidden)); return; }
  if ($('#more') && !$('#more').hidden && !e.target.closest('#more')) { $('#more').hidden = true; $('#more-btn').setAttribute('aria-expanded', 'false'); }
  const cr = e.target.closest('[data-copy-req]');
  if (cr && card.intent) {
    const it = card.intent;
    navigator.clipboard.writeText(`${location.origin}${location.pathname}#pay-${it.to}-${it.amount}-${it.ref}`)
      .then(() => { cr.textContent = 'Copied. Open the Cinder app'; }, () => { cr.textContent = 'Couldn’t copy'; });
    return;
  }
  const tr = e.target.closest('tr[data-href]');
  if (tr && !e.target.closest('a')) { location.hash = tr.dataset.href; return; }
  if (e.target.closest('[data-node]') || e.target.closest('#netpill')) {
    e.preventDefault();
    $('#nodeurl').value = API || '';
    $('#nodedlg').showModal();
  }
});
$('#nodeform').onsubmit = async e => {
  if (e.submitter?.value !== 'save') return;
  const v = $('#nodeurl').value.trim().replace(/\/$/, '');
  if (!/^https?:\/\//.test(v)) return;
  store.set('node', v); API = v; lastTip = null;
  await tick(); route();
};

window.addEventListener('hashchange', route);
document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(); });
window.addEventListener('online', () => { lastFind = 0; tick(); });
await findNode();
paintStatus();
route();
setInterval(tick, 5000);

// Keep a Home Screen app current: if the published page is newer than the one on screen, refresh the
// cached files once and reload. (GitHub Pages lets browsers keep files for 10 minutes.)
(async () => {
  try {
    const mine = document.querySelector('meta[name=build]')?.content;
    if (!mine || !location.protocol.startsWith('http')) return;
    const html = await (await fetch(location.pathname, { cache: 'no-store' })).text();
    const theirs = (html.match(/name="build" content="([^"]+)"/) || [])[1];
    if (!theirs || theirs === mine) return;
    let done = false;
    try { done = sessionStorage.getItem('cinder.reloaded') === theirs; sessionStorage.setItem('cinder.reloaded', theirs); } catch {}
    if (done) return;
    await Promise.all(['style.css', 'app.js', 'cindercrypto.js', 'discover.js', 'config.js', 'pay.js', location.pathname].map(f => fetch(f, { cache: 'reload' }).catch(() => {})));
    location.reload();
  } catch {}
})();
