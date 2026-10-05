// A pure function of time: seek(t) puts every element exactly where it belongs at t seconds.
const EASE = { out: 'cubic-bezier(.16,1,.3,1)', spring: 'cubic-bezier(.34,1.56,.64,1)', inout: 'cubic-bezier(.65,0,.35,1)', in: 'cubic-bezier(.5,0,.9,.4)', lin: 'linear' };
const ANIMS = [], TICKS = [], SFX = []; window.SFX = SFX;
const $q = s => typeof s === 'string' ? [...document.querySelectorAll(s)] : (Array.isArray(s) || s instanceof NodeList) ? [...s] : [s];
const fx = (t, type, vol = 1) => SFX.push({ t: +t.toFixed(3), type, vol });
function A(target, frames, o = {}) {
  const { at = 0, dur = .5, ease = EASE.out, stagger = 0, fill = 'both' } = o;
  $q(target).forEach((el, i) => { const a = el.animate(frames, { duration: dur * 1000, delay: (at + i * stagger) * 1000, easing: ease, fill }); a.pause(); ANIMS.push(a); });
}
const IN = (t, at, o = {}) => A(t, [{ opacity: 0, transform: `translateY(${o.y ?? 50}px) scale(${o.s ?? .94})` }, { opacity: 1, transform: 'none' }], { at, dur: o.dur ?? .65, ease: o.ease ?? EASE.out, stagger: o.stagger ?? 0 });
const POP = (t, at, o = {}) => A(t, [{ opacity: 0, transform: `scale(${o.s ?? .3})` }, { opacity: 1, transform: 'none' }], { at, dur: o.dur ?? .5, ease: EASE.spring, stagger: o.stagger ?? 0 });
const OUT = (t, at, o = {}) => A(t, [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateY(${o.y ?? -30}px) scale(${o.s ?? 1})` }], { at, dur: o.dur ?? .35, ease: EASE.in, fill: 'forwards' });
const TICK = f => TICKS.push(f);
const sm = x => x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x);
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));

function H(sel, text, at, o = {}) {
  const el = $q(sel)[0];
  el.innerHTML = text.split(' ').map(w => w === '|' ? '<br>' : `<span class="w${w.startsWith('*') ? ' ac' : ''}"><span>${w.replace(/\*/g, '')}</span></span>`).join(' ');
  A(el.querySelectorAll('.w > span'), [{ transform: 'translateY(108%)' }, { transform: 'none' }], { at, dur: o.dur ?? .75, stagger: o.stagger ?? .09 });
}
function scene(id, at, end, ox, oy) {
  const s = '#' + id;
  if (at == null) A(s, [{ visibility: 'visible' }, { visibility: 'visible' }], { at: 0, dur: .01 });
  else A(s, [{ visibility: 'visible', clipPath: `circle(0px at ${ox}px ${oy}px)` }, { visibility: 'visible', clipPath: `circle(2400px at ${ox}px ${oy}px)` }], { at, dur: .8, ease: EASE.inout });
  if (end != null) A(s, [{ visibility: 'visible' }, { visibility: 'hidden' }], { at: end, dur: .001, fill: 'forwards' });
}
// Position of an element inside its phone screen (layout units, unaffected by scaling).
function pos(el, dx = 0, dy = 0) {
  let x = el.offsetWidth / 2 + dx, y = el.offsetHeight / 2 + dy, n = el;
  while (n && !n.classList.contains('screen')) { x += n.offsetLeft; y += n.offsetTop; n = n.offsetParent; }
  return [x, y];
}
function finger(id, ripId, path) { // path: [[t,x,y,tap?], ...] absolute times
  const f = document.getElementById(id), r = document.getElementById(ripId);
  const [t0, x0, y0] = path[0];
  A(f, [{ opacity: 0, transform: `translate(${x0}px,${y0}px) scale(.6)` }, { opacity: 1, transform: `translate(${x0}px,${y0}px) scale(1)` }], { at: t0 - .25, dur: .25, fill: 'both' });
  for (let i = 1; i < path.length; i++) {
    const [ta, xa, ya] = path[i - 1], [tb, xb, yb, tap] = path[i];
    A(f, [{ transform: `translate(${xa}px,${ya}px) scale(1)` }, { transform: `translate(${xb}px,${yb}px) scale(1)` }], { at: ta, dur: Math.max(.05, tb - ta - (tap ? .0 : 0)), ease: EASE.inout, fill: 'both' });
    if (tap) {
      A(f, [{ transform: `translate(${xb}px,${yb}px) scale(1)` }, { transform: `translate(${xb}px,${yb}px) scale(.78)`, offset: .45 }, { transform: `translate(${xb}px,${yb}px) scale(1)` }], { at: tb, dur: .22, fill: 'both', ease: EASE.out });
      A(r, [{ opacity: 0, transform: `translate(${xb}px,${yb}px) scale(.6)` }, { opacity: .9, transform: `translate(${xb}px,${yb}px) scale(1)`, offset: .2 }, { opacity: 0, transform: `translate(${xb}px,${yb}px) scale(2.6)` }], { at: tb, dur: .6, fill: 'both', ease: EASE.out });
      fx(tb, 'tap');
    }
  }
  const [te, xe, ye] = path[path.length - 1];
  A(f, [{ opacity: 1 }, { opacity: 0 }], { at: te + .45, dur: .25, fill: 'forwards' });
}

// ---------- timing ----------
const Ls = TIMING.lines, TOTAL = TIMING.total;
const S = i => Ls[i].start, E = i => Ls[i].start + Ls[i].dur;
const eB = S(2) - .55, eC = S(3) - .5, eD = S(6) - .55, eJ = S(7) - .6, eG = S(10) - .55, eH = S(12) - .55, eI = S(13) - .55;

scene('sA', null, eB + .85);
scene('sB', eB, eC + .85, 540, 1750); fx(eB, 'whoosh');
scene('sC', eC, eD + .85, 1000, 320); fx(eC, 'whoosh');
scene('sD', eD, eJ + .85, 90, 1000); fx(eD, 'whoosh');
scene('sJ', eJ, eG + .85, 540, 980); fx(eJ - .5, 'riser');
scene('sG', eG, eH + .85, 980, 1750); fx(eG, 'whoosh');
scene('sH', eH, eI + .85, 540, 960); fx(eH, 'whoosh');
scene('sI', eI, null, 540, 320); fx(eI, 'whoosh');

// ---------- A: title ----------
A('#aGlow', [{ opacity: 0, transform: 'scale(.6)' }, { opacity: 1, transform: 'scale(1)' }], { at: .1, dur: 1.6 });
A('#aBars .bw', [{ transform: 'scaleY(0)' }, { transform: 'none' }], { at: .15, dur: .85, stagger: .07, ease: EASE.spring });
H('#aTitle', 'Musicly', .45, { dur: .9 }); fx(.5, 'hit');
IN('#aEth', 1.15, { y: 20, dur: .8 });
H('#aSub', 'Every song you love. | *One* *calm* *player.*', S(1) - .05, { stagger: .1 });
TICK(t => {
  $q('#aBars .bw i').forEach((b, i) => b.style.transform = `scaleY(${t < 1.2 ? 1 : .86 + .14 * Math.sin(t * 5.5 + i * .9)})`);
  document.getElementById('aGlow').style.filter = `brightness(${1 + .12 * Math.sin(t * 2.4)})`;
});

// ---------- B: Telegram ----------
{
  const T = S(2);
  IN('#sB .hdr, #sB .kick', eB + .45, { y: 20 });
  H('#bH', 'Post it. | *Play* *it.*', eB + .4);
  IN('#bTg', eB + .35, { y: 90, dur: .8 });
  POP('#bBot', T + 1.55); fx(T + 1.55, 'pop');
  $q('#bTg .bb').forEach((b, i) => { POP(b, T + 2.35 + i * .5, { s: .6 }); fx(T + 2.35 + i * .5, 'pop', .8); });
  POP('#bBridge', T + 3.55, { s: .2 }); fx(T + 3.55, 'pop');
  IN('#bMs', T + 3.6, { y: 90, dur: .8 });
  $q('#bMs .mr').forEach((r, i) => { IN(r, T + 4.0 + i * .28, { y: 30, dur: .5 }); POP(r.querySelector('.ck'), T + 4.3 + i * .28, { s: 0 }); fx(T + 4.3 + i * .28, 'ding', .5); });
  POP('#bSaved', T + 4.55, { s: .5 });
  TICK(t => { document.getElementById('bRing').style.transform = `rotate(${t * 60}deg)`; });
}

// ---------- C: your music · radio · lyrics ----------
{
  const T3 = S(3), T4 = S(4), T5 = S(5);
  IN('#sC .hdr', eC + .45, { y: 20 });
  A('#cPhone', [{ opacity: 0, transform: 'translateY(260px)' }, { opacity: 1, transform: 'none' }], { at: eC + .25, dur: .95 });
  H('#cK1', 'Your own | *music.*', eC + .45); OUT('#cK1', T4 - .2);
  H('#cK2', 'Live | *radio.*', T4 - .05); OUT('#cK2', T5 - .2);
  H('#cK3', 'Synced | *lyrics.*', T5 - .05);
  POP('#cP1 .tl', eC + .85, { s: .6, stagger: .1 });
  // pane swaps
  A('#cP1', [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateX(-28%) scale(.97)' }], { at: T4 - .1, dur: .6, ease: EASE.inout, fill: 'forwards' });
  A('#cP2', [{ transform: 'translateX(100%)' }, { transform: 'none' }], { at: T4 - .1, dur: .6, ease: EASE.inout });
  A('#cP2 #rRows .song', [{ opacity: 0, transform: 'translateX(60px)' }, { opacity: 1, transform: 'none' }], { at: T4 + .1, dur: .5, stagger: .12 });
  A('#cP2', [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateX(-28%) scale(.97)' }], { at: T5 - .1, dur: .6, ease: EASE.inout, fill: 'forwards' });
  A('#cP3', [{ transform: 'translateX(100%)' }, { transform: 'none' }], { at: T5 - .1, dur: .6, ease: EASE.inout });
  fx(T4 - .1, 'swish', .7); fx(T5 - .1, 'swish', .7);
  TICK(t => {
    $q('#rEq i').forEach((b, i) => b.style.height = `${6 + 16 * Math.abs(Math.sin(t * 7 + i * 1.7))}px`);
    // lyrics: the highlight walks down the lines
    const u = Math.max(0, (t - (T5 + .1)) / .5), k = Math.floor(u), idx = Math.min(1 + k + sm(u - k), 5);
    document.getElementById('lyList').style.transform = `translateY(${140 - 64 * idx}px)`;
    document.getElementById('lyHi').style.transform = 'translateY(140px)';
    $q('#lyList .ly').forEach((l, i) => { const d = Math.abs(idx - i); l.style.color = d < .5 ? '#fff' : d < 1.5 ? '#6B6E7A' : '#3A3D47'; });
    // gentle float
    document.getElementById('cPhone').style.transform = `translateY(${Math.sin(t * 1.3) * 7}px)`;
  });
}

// ---------- D: lock screen & widgets ----------
{
  const T = S(6), tSwap = T + 1.45;
  IN('#sD .hdr', eD + .45, { y: 20 });
  H('#dK1', 'Lock | *screen.*', eD + .45); OUT('#dK1', tSwap - .2);
  H('#dK2', 'Home | *widgets.*', tSwap - .05);
  A('#dPhone', [{ opacity: 0, transform: 'translateY(260px)' }, { opacity: 1, transform: 'none' }], { at: eD + .3, dur: .95 });
  IN('#lkCard', eD + .85, { y: 80, dur: .8 });
  const nxt = pos(document.getElementById('lkNext'));
  finger('dFing', 'dRip', [[T + .3, nxt[0] + 40, nxt[1] + 170], [T + .85, nxt[0], nxt[1], true]]);
  A('#lkT1', [{ opacity: 1 }, { opacity: 0 }], { at: T + .88, dur: .25, fill: 'forwards' });
  A('#lkT2', [{ opacity: 0, transform: 'translateY(10px)' }, { opacity: 1, transform: 'none' }], { at: T + .88, dur: .35 });
  A('#dP1', [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateX(-28%) scale(.97)' }], { at: tSwap, dur: .65, ease: EASE.inout, fill: 'forwards' });
  A('#dP2', [{ transform: 'translateX(100%)' }, { transform: 'none' }], { at: tSwap, dur: .65, ease: EASE.inout });
  POP('#wg4', tSwap + .5, { s: .7 }); POP('#wg2', tSwap + .75, { s: .5 }); fx(tSwap + .5, 'pop'); fx(tSwap + .75, 'pop', .8);
  A('#dP2 .art[data-plain]', [{ opacity: 0, transform: 'scale(.4)' }, { opacity: 1, transform: 'none' }], { at: tSwap + .95, dur: .4, ease: EASE.spring, stagger: .05 });
  fx(tSwap, 'swish', .7);
  TICK(t => { document.getElementById('lkBar').style.width = (t < T + .88 ? 40 + (t - eD) * 1.2 : 4 + (t - T - .88) * 1.5) + '%'; document.getElementById('dPhone').style.transform = `translateY(${Math.sin(t * 1.3) * 7}px)`; });
}

// ---------- J: JAM ----------
{
  const T7 = S(7), T8 = S(8), T9 = S(9), tCode = T8 + .55, CODE = '8ADT-VDQY-BAN7';
  // intro
  POP('#jIcon', eJ + .5, { s: .2, dur: .7 }); fx(E(7) - .52, 'hit');
  H('#jWord', '*Jam.*', E(7) - .5, { dur: .85 });
  A('#jIntro', [{ opacity: 1, transform: 'scale(1)' }, { opacity: 0, transform: 'scale(1.18)' }], { at: T8 - .1, dur: .45, ease: EASE.in, fill: 'forwards' });
  A('#sJ .hdr, #sJ .kick', [{ opacity: 0 }, { opacity: 1 }], { at: T8 + .1, dur: .4 });
  // share the code
  H('#jH', 'Share the *code.*', T8 - .05);
  A('#jTicket', [{ opacity: 0, transform: 'translateY(-260px) rotate(-3deg)' }, { opacity: 1, transform: 'none' }], { at: T8 + .1, dur: .85, ease: EASE.spring });
  fx(T8 + .15, 'pop');
  for (let i = 0; i < CODE.length; i++) fx(tCode + i * (1.0 / CODE.length), 'type', .45);
  // host phone
  A('#jPhM', [{ opacity: 0, transform: 'translateY(900px)' }, { opacity: 1, transform: 'none' }], { at: T8 + 1.0, dur: 1.0, ease: EASE.spring }); fx(T8 + 1.0, 'swish', .8);
  // friends join
  const tR = T9 + .3, tS = T9 + 1.2;
  A('#jPhL', [{ opacity: 0, transform: 'translateX(-600px)' }, { opacity: 1, transform: 'none' }], { at: tR, dur: 1.0, ease: EASE.spring });
  A('#jPhR', [{ opacity: 0, transform: 'translateX(600px)' }, { opacity: 1, transform: 'none' }], { at: tS, dur: 1.0, ease: EASE.spring });
  POP('#jTagL', tR + .55, { s: .5 }); POP('#jTagR', tS + .55, { s: .5 });
  fx(tR + .5, 'join'); fx(tS + .5, 'join');
  POP('#jSync', T9 + 3.0, { s: .6 }); fx(T9 + 3.0, 'ding');
  const tSync = T9 + 3.0;
  TICK(t => {
    // rings in the intro
    $q('#jRings .ring').forEach((r, i) => { const p = (((t - eJ - .3 - i * .45) % 1.8) + 1.8) % 1.8 / 1.8; r.style.opacity = t < eJ + .3 ? 0 : .7 * (1 - p); r.style.transform = `scale(${.5 + p * 1.5})`; });
    // code typing
    const n = clamp(Math.floor((t - tCode) / 1.0 * CODE.length) + 1, 0, CODE.length);
    document.getElementById('jCode').innerHTML = t < tCode ? '&nbsp;' : CODE.slice(0, n) + (n < CODE.length && Math.floor(t * 6) % 2 ? '<span style="opacity:.5">|</span>' : '');
    // people count
    document.getElementById('jPeople').textContent = `You're hosting · ${t < tR + .5 ? '1 person' : t < tS + .5 ? '2 people' : '3 people'}`;
    // playback in step on every phone
    const el = Math.max(0, t - (T9 - .5)), pct = 30 + el * 3.0;
    $q('#sJ .jbar').forEach(b => b.style.width = pct + '%');
    $q('#sJ .jt').forEach(b => b.textContent = `1:${String(24 + Math.floor(el)).padStart(2, '0')}`);
    const beat = t > tSync - .6 ? Math.pow(Math.max(0, Math.cos((t - tSync) * 2 * Math.PI / .6)), 4) : 0;
    $q('#sJ .jcover').forEach(c => c.style.transform = `scale(${1 + .035 * beat})`);
    const dot = document.getElementById('jSyncDot'); dot.style.boxShadow = `0 0 0 ${6 + 10 * beat}px rgba(82,208,139,${.25 * (1 - beat * .3)})`;
  });
}

// ---------- G: add songs · control ----------
{
  const T = S(10), T11 = S(11);
  const q1 = document.getElementById('gQ2'), q2 = document.getElementById('gQ3');
  IN('#sG .hdr', eG + .45, { y: 20 });
  H('#gH1', 'Add *songs.*', eG + .45); OUT('#gH1', T11 - .3);
  H('#gH2', 'In *control.*', T11 - .1);
  A('#gPhone', [{ opacity: 0, transform: 'translateY(260px)' }, { opacity: 1, transform: 'none' }], { at: eG + .25, dur: .95 });
  const add = pos(document.getElementById('gAdd')), rowsH = $q('#gListH .gr'), segB = pos(document.getElementById('gSegB')), rowM = pos($q('#gListM .song')[0]);
  const rowG = pos(rowsH[1]);
  // 1) open the sheet, pick a song from the host's library
  finger('gFing', 'gRip', [[T + .15, 330, 740], [T + .6, add[0], add[1], true], [T + 1.25, 330, 560], [T + 1.7, rowG[0] + 20, rowG[1], true], [T + 2.35, 330, 700]]);
  A('#gSheet', [{ transform: 'translateY(660px)' }, { transform: 'none' }], { at: T + .72, dur: .6, ease: EASE.out });
  A('#gSheet', [{ transform: 'none' }, { transform: 'translateY(660px)' }], { at: T + 2.05, dur: .5, ease: EASE.inout });
  A('#gSheet', [{ transform: 'translateY(660px)' }, { transform: 'none' }], { at: T + 2.8, dur: .5, ease: EASE.out, fill: 'forwards' });
  A('#gSheet', [{ transform: 'none' }, { transform: 'translateY(660px)' }], { at: T + 4.0, dur: .5, ease: EASE.inout, fill: 'forwards' });
  POP('#gTagA', T + .9, { s: .6 }); OUT('#gTagA', T + 2.55, { y: 0 });
  A(rowsH[1].querySelector('.gplus'), [{ opacity: 1 }, { opacity: 0 }], { at: T + 1.75, dur: .15, fill: 'forwards' });
  POP(rowsH[1].querySelector('.gck'), T + 1.78, { s: 0 }); fx(T + 1.78, 'ding', .6);
  IN(q1, T + 2.45, { y: 30, dur: .6 }); A('#gQ2g', [{ opacity: 1 }, { opacity: 0 }], { at: T + 2.9, dur: 1.2, fill: 'both' });
  A('#gToast', [{ opacity: 0, transform: 'translateY(-90px)' }, { opacity: 1, transform: 'none' }], { at: T + 2.45, dur: .5, ease: EASE.spring }); A('#gToast', [{ opacity: 1 }, { opacity: 0 }], { at: T + 3.3, dur: .3, fill: 'forwards' }); fx(T + 2.45, 'pop');
  // 2) send a song from the guest's own phone (the sheet re-opens on "My songs")
  A('#gSeg', [{ transform: 'none' }, { transform: 'translateX(188px)' }], { at: T + 2.6, dur: .01 });
  A('#gSegA', [{ color: '#1C1D22' }, { color: '#fff' }], { at: T + 2.6, dur: .01 });
  A('#gSegB', [{ color: '#fff' }, { color: '#1C1D22' }], { at: T + 2.6, dur: .01 });
  A('#gListH', [{ opacity: 1 }, { opacity: 0 }], { at: T + 2.6, dur: .01, fill: 'forwards' });
  A('#gListM', [{ opacity: 0 }, { opacity: 1 }], { at: T + 2.6, dur: .01 });
  POP('#gTagB', T + 2.85, { s: .6 }); OUT('#gTagB', T11 - .3, { y: 0 });
  finger('gFing', 'gRip', [[T + 3.0, 330, 600], [T + 3.4, rowM[0] + 20, rowM[1], true], [T + 4.2, 330, 700]]);
  TICK(t => { const p = clamp((t - (T + 3.45)) / .6); document.getElementById('gUpC').style.strokeDashoffset = 82 * (1 - sm(p)); });
  IN(q2, T + 4.4, { y: 30, dur: .6 }); A('#gQ3g', [{ opacity: 1 }, { opacity: 0 }], { at: T + 4.85, dur: 1.0, fill: 'both' });
  A('#gToast', [{ opacity: 0, transform: 'translateY(-90px)' }, { opacity: 1, transform: 'none' }], { at: T + 4.4, dur: .5, ease: EASE.spring, fill: 'backwards' });
  fx(T + 4.4, 'pop');
  // 3) the host stays in control
  A('#gP1', [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateX(-28%) scale(.97)' }], { at: T11 - .1, dur: .6, ease: EASE.inout, fill: 'forwards' });
  A('#gP2', [{ transform: 'translateX(100%)' }, { transform: 'none' }], { at: T11 - .1, dur: .6, ease: EASE.inout }); fx(T11 - .1, 'swish', .7);
  A('#gGuestCtl, #gGuestNote', [{ opacity: .22 }, { opacity: .22 }, { opacity: 1 }], { at: 0, dur: T11 + 1.0, fill: 'both' });
  const sw = pos(document.getElementById('gSw')), xr = pos(document.getElementById('gChipR').querySelector('.b'));
  finger('gFing2', 'gRip2', [[T11 + .3, 330, 640], [T11 + .85, sw[0], sw[1], true], [T11 + 1.7, xr[0], xr[1] + 6, true], [T11 + 2.7, 330, 660]]);
  A('#gSwK', [{ transform: 'none' }, { transform: 'translateX(24px)' }], { at: T11 + .9, dur: .25, ease: EASE.spring });
  A('#gSw', [{ backgroundColor: '#3a3c45' }, { backgroundColor: '#FF6B4A' }], { at: T11 + .9, dur: .25 });
  A('#gChipR', [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.4)' }], { at: T11 + 1.75, dur: .3, ease: EASE.in, fill: 'forwards' });
  A('#gToast2', [{ opacity: 0, transform: 'translateY(-90px)' }, { opacity: 1, transform: 'none' }], { at: T11 + 1.85, dur: .5, ease: EASE.spring }); A('#gToast2', [{ opacity: 1 }, { opacity: 0 }], { at: T11 + 3.0, dur: .3, fill: 'forwards' }); fx(T11 + 1.85, 'pop');
  TICK(t => {
    document.getElementById('gBar').style.width = (40 + Math.max(0, t - eG) * 2) + '%';
    document.getElementById('gHost').textContent = `You're hosting · ${t < T11 + 1.8 ? 3 : 2} people`;
    $q('#sG .eq i').forEach((b, i) => b.style.height = `${5 + 15 * Math.abs(Math.sin(t * 7 + i * 1.7))}px`);
    document.getElementById('gPhone').style.transform = `translateY(${Math.sin(t * 1.3) * 7}px)`;
  });
}

// ---------- H: same room ----------
{
  const T = S(12);
  IN('#sH .hdr, #sH .kick', eH + .45, { y: 20 });
  H('#hH', 'Same room? | *No* *internet.*', eH + .45);
  POP('#hCenter', eH + .5, { s: .2, dur: .7 }); fx(eH + .55, 'pop');
  $q('#sH .hp').forEach((p, i) => { POP(p, T + .1 + i * .22, { s: .3, dur: .55 }); fx(T + .1 + i * .22, 'pop', .7); });
  A('#hRings .hl2', [{ opacity: 0 }, { opacity: 1 }], { at: T + .6, dur: .6, stagger: .1 });
  POP('#hChip', T + 1.35, { s: .6 });
  TICK(t => {
    $q('#hRings .hr').forEach((c, i) => { const p = (((t - eH - .6 - i * .55) % 2.2) + 2.2) % 2.2 / 2.2; c.setAttribute('r', 130 + p * 330); c.setAttribute('opacity', t < eH + .6 ? 0 : .6 * (1 - p)); });
    $q('#hRings .hl2').forEach(l => l.style.strokeDashoffset = -t * 60);
  });
}

// ---------- I: outro ----------
{
  const T13 = S(13), T14 = S(14);
  A('#iGlow', [{ opacity: 0, transform: 'scale(.6)' }, { opacity: 1, transform: 'scale(1)' }], { at: eI + .1, dur: 1.4 });
  A('#iBars .bw', [{ transform: 'scaleY(0)' }, { transform: 'none' }], { at: eI + .3, dur: .8, stagger: .06, ease: EASE.spring });
  H('#iTitle', 'Musicly', T13 - .05, { dur: .8 });
  H('#iTag', 'Your music, | *together.*', T13 + .75, { stagger: .12 });
  IN('#iChips .pill', T13 + 1.4, { y: 40, stagger: .1, dur: .55 });
  POP('#iBtn', T14 - .15, { s: .6, dur: .6 }); fx(T14 - .12, 'hit');
  IN('#iUrl', T14 + .5, { y: 20 });
  fx(T13 + .0, 'swell');
  TICK(t => {
    $q('#iBars .bw i').forEach((b, i) => b.style.transform = `scaleY(${.86 + .14 * Math.sin(t * 5.5 + i * .9)})`);
    const pulse = t > T14 ? 1 + .035 * Math.sin((t - T14) * 5) : 1;
    document.getElementById('iBtn').firstElementChild.style.transform = `scale(${pulse})`;
  });
}

// ---------- captions ----------
const CAPS = [];
Ls.forEach(l => {
  const words = l.text.split(/\s+/), chunks = [];
  let cur = [];
  words.forEach((w, i) => { cur.push(w); if (/[.,?!]$/.test(w) && cur.length >= 2 || cur.length >= 5) { chunks.push(cur.join(' ')); cur = []; } });
  if (cur.length) { if (chunks.length && cur.length < 2) chunks[chunks.length - 1] += ' ' + cur.join(' '); else chunks.push(cur.join(' ')); }
  const total = chunks.reduce((a, c) => a + c.length + 4, 0); let t0 = l.start;
  chunks.forEach(c => { const d = (c.length + 4) / total * l.dur; CAPS.push({ a: t0, b: t0 + d, text: c.replace(/[.,]$/, m => m === '.' ? '' : '') }); t0 += d; });
});
let capLast = -1;
TICK(t => {
  const cap = document.getElementById('cap'), span = cap.firstElementChild;
  const i = CAPS.findIndex(c => t >= c.a - .02 && t < c.b + .12);
  if (i < 0) { cap.style.opacity = 0; return; }
  if (i !== capLast) { span.textContent = CAPS[i].text; capLast = i; }
  const c = CAPS[i];
  cap.style.opacity = clamp((t - c.a) / .12) * clamp((c.b + .12 - t) / .1);
  cap.style.transform = `translateY(${(1 - clamp((t - c.a) / .18)) * 16}px)`;
});

window.TOTAL = TOTAL;
window.seek = t => { ANIMS.forEach(a => a.currentTime = t * 1000); TICKS.forEach(f => f(t)); };
window.READY = true;
