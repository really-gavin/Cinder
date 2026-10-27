// Pay with Cinder: a button for any online shop.
//
//   <div id="pay"></div>
//   <script type="module">
//     import { payButton } from 'https://really-gavin.github.io/Cinder/website/pay.js';
//     payButton(document.getElementById('pay'), {
//       to: 'XXXXXXXXXXXXXXXX',     // the shop's card ID
//       amount: 3.5,                // in CDR
//       name: 'My Shop',            // shown on the customer's phone
//       onPaid: (p) => { … },       // the payment is in a block
//     });
//   </script>
//
// On Android with Cinder Wallet, tapping the button opens the Cinder Pay
// sheet right over the shop's page. Anywhere else it shows a code to scan
// with a phone. Either way the button watches the network and reports back
// when the payment lands. It never touches anyone's keys.
import qrcode from './vendor/qrcode.js';
import { DISCOVERY, NODES } from './config.js';
import { discoverNode } from './discover.js';

const SITE = new URL('./', import.meta.url).href;   // this website
const ASH = 100_000_000;
const ALPHA = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const isAndroid = /Android/i.test(navigator.userAgent);

export function newRef() {
  return [...crypto.getRandomValues(new Uint8Array(6))].map(x => ALPHA[x & 31]).join('');
}
const toAsh = cdr => Math.round(Number(cdr) * ASH);

/** Link that opens on any phone: the Cinder website wallet with the payment filled in. */
export function webLink({ to, amount, ref }) {
  return `${SITE}#pay-${to}-${toAsh(amount)}-${ref}`;
}

/** Android link that opens the Cinder Pay sheet in the wallet app (falls back to the website). */
export function appLink(p) {
  const q = new URLSearchParams({ to: p.to, amount: String(toAsh(p.amount)), ref: p.ref });
  if (p.name) q.set('name', p.name);
  return `intent://pay?${q}#Intent;scheme=cinderpay;package=app.cinder.wallet;` +
    `S.browser_fallback_url=${encodeURIComponent(webLink(p) + '-web')};end`;
}

let nodeUrl = null;
async function node() {
  if (nodeUrl) return nodeUrl;
  // A fixed node, else the one the node phone announced, else the node that serves this site (if it is one).
  for (const n of [...NODES, await discoverNode(DISCOVERY), new URL(SITE).origin].filter(Boolean)) {
    try { if ((await fetch(n + '/api/status')).ok) return (nodeUrl = n); } catch {}
  }
  throw new Error("Can't reach the Cinder network right now.");
}

/**
 * Watches for the payment. Calls onChange('waiting' | 'seen' | 'paid', info).
 * Returns a function that stops watching.
 */
export function watch({ to, amount, ref }, onChange) {
  let stopped = false, last = '';
  const want = toAsh(amount), tag = `Order ${ref}`;
  const ours = m => typeof m === 'string' && m.startsWith(tag);
  (async function loop() {
    while (!stopped) {
      try {
        const me = await (await fetch(`${await node()}/api/id/${to}`)).json();
        const done = me.history.filter(e => e.kind === 'money' && e.why === 'payment' && e.to === to && ours(e.memo));
        const coming = me.pending.filter(t => t.type === 'pay' && t.to === to && ours(t.memo));
        const got = done.reduce((n, e) => n + e.amount, 0), all = got + coming.reduce((n, t) => n + t.amount, 0);
        const state = got >= want ? 'paid' : all >= want ? 'seen' : 'waiting';
        if (state !== last) { last = state; onChange(state, { from: (done[0] || coming[0])?.from, receipt: done[0]?.ref }); }
        if (state === 'paid') return;
      } catch (e) { onChange('error', { message: e.message }); nodeUrl = null; }
      await new Promise(r => setTimeout(r, 3000));
    }
  })();
  return () => { stopped = true; };
}

const CSS = `
.cpay{font:600 17px/1 system-ui,sans-serif;display:inline-flex;align-items:center;gap:10px;padding:14px 22px;border:0;border-radius:10px;
  background:#1f1e1b;color:#ebe8df;cursor:pointer;min-height:50px}
.cpay svg{width:28px;height:20px}.cpay:focus-visible{outline:3px solid #f3d317;outline-offset:2px}
.cpay-modal{position:fixed;inset:0;background:rgba(31,30,27,.6);display:grid;place-items:end center;z-index:2147483000}
.cpay-sheet{background:#1f1e1b;color:#ebe8df;width:min(440px,100%);border-radius:18px 18px 0 0;padding:22px 22px 28px;font:16px/1.45 system-ui,sans-serif;display:grid;gap:12px}
.cpay-sheet h3{margin:0;font:800 22px/1 system-ui,sans-serif}.cpay-amt{font:800 52px/1 system-ui,sans-serif}
.cpay-qr{background:#fff;padding:8px;border-radius:6px;width:min(260px,70vw);justify-self:center}
.cpay-muted{color:#a29d90;font-size:14px}.cpay-status{padding:12px 14px;border-radius:8px;background:#2e2d28}
.cpay-status.seen{background:#f3d317;color:#1f1e1b}.cpay-status.paid{background:#7fc58a;color:#1f1e1b;font-weight:700}
.cpay-x{justify-self:end;background:none;border:0;color:#a29d90;font:16px system-ui;cursor:pointer}`;
const MARK = '<svg viewBox="0 0 34 24" aria-hidden="true"><path fill="#f3d317" fill-rule="evenodd" d="M0 0h34v24H0zM5 5v14h10V5zm14 0v14h10V5z"/></svg>';

/** Renders a "Pay with Cinder" button into el. */
export function payButton(el, opts) {
  if (!document.getElementById('cpay-css')) {
    const s = document.createElement('style'); s.id = 'cpay-css'; s.textContent = CSS; document.head.append(s);
  }
  const b = document.createElement('button');
  b.type = 'button'; b.className = 'cpay'; b.innerHTML = `${MARK}<span>Pay with Cinder</span>`;
  el.replaceChildren(b);
  b.onclick = () => start(opts);
  return b;
}

function start(opts) {
  const p = { ...opts, ref: opts.ref || newRef() };
  const modal = document.createElement('div');
  modal.className = 'cpay-modal';
  const amountTxt = Number(p.amount).toLocaleString('en-US', { maximumFractionDigits: 8 });
  modal.innerHTML = `<div class="cpay-sheet" role="dialog" aria-label="Pay with Cinder">
    <button class="cpay-x" type="button">Cancel</button>
    <h3>${MARK.replace('<svg', '<svg style="width:26px;height:18px;vertical-align:-2px"')} Cinder Pay</h3>
    <div class="cpay-muted">${esc(p.name || 'Pay')}</div>
    <div class="cpay-amt">${amountTxt} <span class="cpay-muted">CDR</span></div>
    <div class="cpay-body"></div>
    <div class="cpay-status">Waiting for payment…</div>
    <div class="cpay-muted">Order ${p.ref}</div></div>`;
  document.body.append(modal);
  const body = modal.querySelector('.cpay-body'), status = modal.querySelector('.cpay-status');
  if (isAndroid) {
    body.innerHTML = `<div class="cpay-muted">Confirm in Cinder Wallet. Don't have it? You'll be taken to the Cinder website wallet.</div>`;
    location.href = appLink(p);                       // opens the Cinder Pay sheet over this page
  } else {
    const q = qrcode(0, 'M'); q.addData(webLink(p)); q.make();
    body.innerHTML = `<div class="cpay-muted">Scan with your phone to pay.</div><div class="cpay-qr">${q.createSvgTag({ cellSize: 5, margin: 2, scalable: true })}</div>`;
  }
  const stop = watch(p, (state, info) => {
    status.className = 'cpay-status ' + state;
    status.textContent = { waiting: 'Waiting for payment…', seen: 'Payment on its way. Confirming…',
      paid: 'Paid ✓', error: info.message }[state];
    if (state === 'paid') { setTimeout(close, 1800); opts.onPaid?.({ ...info, ref: p.ref }); }
  });
  function close() { stop(); modal.remove(); }
  modal.querySelector('.cpay-x').onclick = close;
}

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
