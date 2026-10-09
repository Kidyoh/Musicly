// Builds every scene's markup. Animation lives in timeline.js; ids are the hooks.
const G = c => `&#x${c};`; // Phosphor glyph
const bars = (hs, w, gap, cols, h) => hs.map((v, i) => `<div class="bw" style="height:${v * h}px"><i style="display:block;width:${w}px;height:100%;border-radius:${w}px;background:${cols[i % cols.length]}"></i></div>`).join('');
const rows = (items) => items.map(([art, t, s, extra]) => `<div class="song"><div class="art ${art}"></div><div><div class="t">${t}</div><div class="s">${s}</div></div>${extra || ''}</div>`).join('');
const stat = (c = '') => `<div class="sb" style="${c}"><span>9:41</span><span><span class="f">${G('e4ea')}</span> 77%</span></div>`;
const phone = (id, inner, h = 800) => `<div class="phone" id="${id}" style="height:${h}px"><div class="screen">${inner}</div></div>`;
const place = (id, x, y, k, rot, inner) => `<div class="place" id="${id}" style="left:${x}px;top:${y}px;transform:scale(${k}) rotate(${rot}deg)">${inner}</div>`;
const sdots = (n, at) => Array.from({ length: n }, (_, i) => { const v = .25 + .75 * Math.abs(Math.sin(i * .55) * Math.cos(i * .21)); return `<i style="height:${Math.round(v * 100)}%;opacity:${i / n < at ? 1 : .28}"></i>`; }).join('');

// ---------- phone screens ----------
const homePane = () => `${stat()}
<div class="hd"><div><div class="g">Good evening</div><div class="n">Musicly</div></div><div class="ic"><span class="i">${G('e68e')}</span><span class="i">${G('e434')}</span><span class="i">${G('e472')}</span></div></div>
<div class="mix"><div class="k">MADE FOR YOU</div><div class="ti">Your mix</div><div style="font-size:12.5px;opacity:.9;margin-top:4px">Abel K., Selam Band and more</div>
  <div class="btn"><span class="f">${G('e3d0')}</span>Play mix</div>
  <div class="art a5" style="position:absolute;right:-16px;top:30px;width:128px;height:128px;border-radius:20px;transform:rotate(10deg);box-shadow:0 10px 30px rgba(0,0,0,.35)"></div></div>
<div class="sec">Jump back in</div>
<div class="tiles"><div class="tl"><div class="art a3"></div><div class="t">Slow Rain</div><div class="s">Abel K.</div></div><div class="tl"><div class="art a4"></div><div class="t">Golden Hour</div><div class="s">Mimi &amp; the Bells</div></div><div class="tl"><div class="art a2"></div><div class="t">Addis Jazz FM</div><div class="s">Live radio</div></div></div>
<div class="sec">On this phone</div>
${rows([['a1', 'Addis Nights', 'Selam Band'], ['a6', 'Tizita Blue', 'Hanna M.']])}
<div class="dock"><div class="art a5"></div><div><div class="t" style="font-size:15px;font-weight:600">Tizita Blue</div><div class="s" style="font-size:12px;color:#8A8FA3">Hanna M.</div></div><div class="pp"><span class="f">${G('e39e')}</span></div></div>`;

const radioPane = () => `${stat()}
<div style="padding:16px 22px 0"><div class="g">Real stations, streaming now</div><div style="font-weight:800;font-size:30px;letter-spacing:-1px">Live radio</div></div>
<div style="display:flex;gap:9px;padding:16px 22px 6px;overflow:hidden"><span class="chipd on">Near you</span><span class="chipd">Jazz</span><span class="chipd">Afrobeats</span><span class="chipd">Lo-fi</span></div>
<div id="rRows">${rows([['a2', 'Addis Jazz FM', 'Ethio-jazz · Addis Ababa', '<span class="live" style="margin-left:auto">LIVE</span>'], ['a3', 'Lagos Afrobeats', 'Afrobeats · Lagos', '<span class="live" style="margin-left:auto">LIVE</span>'], ['a4', 'Lo-fi Café', 'Chill · Berlin', '<span class="live" style="margin-left:auto">LIVE</span>'], ['a5', 'Tokyo City Pop', 'City pop · Tokyo', '<span class="live" style="margin-left:auto">LIVE</span>'], ['a1', 'Nairobi Gospel', 'Gospel · Nairobi', '<span class="live" style="margin-left:auto">LIVE</span>']])}</div>
<div class="dock" style="height:78px"><div class="art a2" style="width:52px;height:52px"></div><div><div style="display:flex;gap:7px;align-items:center"><span class="live">LIVE</span><span class="s" style="font-size:11px;color:#8A8FA3">On air now</span></div><div class="t" style="font-size:15px;font-weight:700;margin-top:3px">Addis Jazz FM</div></div>
  <div class="eq" id="rEq" style="margin-left:auto;margin-right:6px"><i></i><i></i><i></i><i></i><i></i></div></div>`;

const lyricsPane = () => `<div style="position:absolute;inset:0;background:radial-gradient(420px 300px at 20% 30%,rgba(255,107,74,.18),transparent 70%),#0E0F12"></div>${stat()}
<div style="display:flex;justify-content:space-between;align-items:center;padding:14px 22px 0;position:relative"><span class="chipd on" style="font-size:12px;padding:7px 12px">Synced</span><span class="g">Lyrics</span><span class="g" style="font-size:20px"><span class="i">${G('e75c')}</span></span></div>
<div id="lyBox" style="position:absolute;left:0;right:0;top:120px;height:430px;overflow:hidden">
  <div id="lyHi" style="position:absolute;left:12px;right:12px;top:0;height:64px;border-radius:18px;background:linear-gradient(90deg,#FF6B4A,rgba(255,107,74,.55))"></div>
  <div id="lyList" style="position:absolute;left:0;right:0;top:0">
    ${['City lights are fading,', 'our song on the radio,', 'singing with the city', 'till the morning comes', 'and we let it go.', 'Hold me one more time,', 'Addis never sleeps.'].map((l, i) => `<div class="ly" data-i="${i}" style="height:64px;line-height:64px;padding-left:28px;font-weight:800;font-size:27px;letter-spacing:-1px;color:#4A4D58;white-space:nowrap">${l}</div>`).join('')}
  </div></div>
<div style="position:absolute;left:18px;right:18px;bottom:20px"><div style="display:flex;gap:12px;align-items:center"><div class="art a1" style="width:56px;height:56px;border-radius:13px"></div><div><div style="font-weight:800;font-size:18px">Addis Nights</div><div class="g">Selam Band</div></div><div class="pp" style="margin-left:auto;width:46px;height:46px;border-radius:50%;background:#fff;color:#1C1D22;display:grid;place-items:center;font-size:20px"><span class="f">${G('e39e')}</span></div></div>
  <div class="scr" data-n="52" data-at=".4" style="margin-top:14px">${sdots(52, .4)}</div></div>`;

const lockPane = () => `<div style="position:absolute;inset:0;background:linear-gradient(170deg,#FF9A6B 0%,#C2185B 52%,#3B2B8F 100%)"></div>
<div style="position:relative">${stat('color:#fff')}
<div style="text-align:center;margin-top:62px;font-size:19px;font-weight:600;opacity:.92">Monday, 5 October</div>
<div style="text-align:center;font-size:122px;font-weight:700;letter-spacing:-5px;line-height:1;margin-top:2px">9:41</div></div>
<div id="lkCard" style="position:absolute;left:16px;right:16px;top:372px;background:rgba(24,12,34,.58);border-radius:30px;padding:20px">
  <div style="display:flex;gap:14px;align-items:center"><div class="art a5" style="width:62px;height:62px;border-radius:14px"></div>
    <div style="position:relative;height:56px;width:210px"><div id="lkT1" style="position:absolute;left:0;top:0"><div style="font-weight:800;font-size:21px">Tizita Blue</div><div style="opacity:.8;font-size:15px;margin-top:3px">Hanna M. · Musicly</div></div>
    <div id="lkT2" style="position:absolute;left:0;top:0"><div style="font-weight:800;font-size:21px">Golden Hour</div><div style="opacity:.8;font-size:15px;margin-top:3px">Mimi &amp; the Bells · Musicly</div></div></div>
    <span class="f" style="margin-left:auto;font-size:24px">${G('e2a8')}</span></div>
  <div style="height:5px;border-radius:5px;background:rgba(255,255,255,.28);margin:18px 0 6px;position:relative"><i id="lkBar" style="position:absolute;left:0;top:0;bottom:0;width:40%;background:#fff;border-radius:5px"></i></div>
  <div style="display:flex;justify-content:space-between;font-size:12px;opacity:.75"><span>1:38</span><span>3:52</span></div>
  <div style="display:flex;justify-content:space-around;align-items:center;font-size:28px;margin-top:10px"><span class="f">${G('e5a4')}</span><span class="f" style="font-size:42px">${G('e39e')}</span><span class="f" id="lkNext">${G('e5a6')}</span></div></div>`;

const homeWPane = () => `<div style="position:absolute;inset:0;background:linear-gradient(175deg,#2B1F6B 0%,#C2185B 58%,#FF8A5B 100%)"></div>
<div style="position:relative">${stat('color:#fff')}</div>
<div id="wg4" style="position:absolute;left:16px;right:16px;top:86px;height:178px;border-radius:30px;background:#1C1D22;padding:16px;display:flex;gap:16px;box-shadow:0 18px 40px rgba(0,0,0,.35)">
  <div class="art a5" style="width:146px;height:146px;border-radius:20px"></div>
  <div><div style="font-size:10.5px;font-weight:800;letter-spacing:2px;color:#FF6B4A">NOW PLAYING</div><div style="font-weight:800;font-size:22px;letter-spacing:-.7px;margin-top:6px">Tizita Blue</div><div class="g" style="margin-top:2px">Hanna M.</div>
  <div style="display:flex;gap:8px;margin-top:18px"><div style="width:40px;height:40px;border-radius:13px;background:#2C2E35;display:grid;place-items:center;font-size:17px"><span class="f">${G('e5a4')}</span></div><div style="width:40px;height:40px;border-radius:13px;background:#fff;color:#1C1D22;display:grid;place-items:center;font-size:17px"><span class="f">${G('e39e')}</span></div><div style="width:40px;height:40px;border-radius:13px;background:#2C2E35;display:grid;place-items:center;font-size:17px"><span class="f">${G('e5a6')}</span></div></div></div></div>
<div id="wg2" style="position:absolute;left:16px;top:284px;width:170px;height:170px;border-radius:30px;overflow:hidden;box-shadow:0 18px 40px rgba(0,0,0,.35)"><div class="art a2" style="width:100%;height:100%;border-radius:0"></div><span class="live" style="position:absolute;left:12px;top:12px;z-index:2">LIVE</span>
  <div style="position:absolute;right:12px;bottom:12px;width:46px;height:46px;border-radius:50%;background:#fff;color:#1C1D22;display:grid;place-items:center;font-size:20px;z-index:2"><span class="f">${G('e3d0')}</span></div></div>
<div style="position:absolute;left:204px;top:292px;right:16px;display:grid;grid-template-columns:repeat(3,1fr);gap:20px 8px;text-align:center;font-size:11px;color:#fff">
  ${['a1', 'a3', 'a4', 'a6', 'a5', 'a2'].map((a, i) => `<div><div class="art ${a}" data-plain style="width:50px;height:50px;border-radius:15px;margin:0 auto"></div><div style="margin-top:5px;opacity:.9">${['Maps', 'Chat', 'Photos', 'Notes', 'Mail', 'Clock'][i]}</div></div>`).join('')}</div>
<div style="position:absolute;left:16px;right:16px;bottom:18px;height:78px;border-radius:30px;background:rgba(255,255,255,.22);display:flex;justify-content:space-around;align-items:center">
  ${['a1', 'a3', 'a4', 'a6'].map(a => `<div class="art ${a}" data-plain style="width:52px;height:52px;border-radius:15px"></div>`).join('')}</div>`;

// Jam screens -------------------------------------------------------
const jamBody = (title, sub, extra = '') => `${stat()}
<div style="padding:14px 22px 0"><div style="font-weight:800;font-size:25px;letter-spacing:-.9px">${title}</div><div class="g" style="margin-top:2px;font-size:13px">${sub}</div></div>
<div class="art a1 jcover" style="width:270px;height:270px;border-radius:26px;margin:16px auto 0"></div>
<div style="text-align:center;font-weight:800;font-size:21px;margin-top:14px;letter-spacing:-.5px">Addis Nights</div><div class="g" style="text-align:center;margin-top:3px;font-size:13px">Selam Band · Added by Sara</div>
<div style="margin:16px 26px 0;height:5px;border-radius:5px;background:#2C2E35;position:relative"><i class="jbar" style="position:absolute;left:0;top:0;bottom:0;width:30%;background:#fff;border-radius:5px"></i></div>
<div style="display:flex;justify-content:space-between;margin:6px 26px 0;font-size:11px;color:#8A8FA3"><span class="jt">1:24</span><span>3:48</span></div>${extra}`;
const jamCtl = `<div style="display:flex;justify-content:center;align-items:center;gap:26px;margin-top:8px;font-size:22px"><span class="f">${G('e5a4')}</span><div style="width:52px;height:52px;border-radius:50%;background:#fff;color:#1C1D22;display:grid;place-items:center"><span class="f">${G('e39e')}</span></div><span class="f">${G('e5a6')}</span></div>`;

// ---------- scenes ----------
const stage = document.getElementById('stage');
const sceneHTML = {};

sceneHTML.A = `<div class="sc" id="sA" style="background:#0E0F12">
  <div id="aGlow" class="abs" style="left:90px;top:360px;width:900px;height:900px;border-radius:50%;background:radial-gradient(circle,rgba(255,107,74,.42),rgba(183,168,255,.12) 45%,transparent 68%)"></div>
  <div id="aBars" class="abs" style="left:0;width:1080px;top:520px;height:420px;display:flex;justify-content:center;align-items:center;gap:30px">${bars([.32, .55, .82, 1, .7, .46, .26], 56, 30, ['#FF6B4A', '#B7A8FF', '#fff', '#fff', '#B7A8FF', '#FF6B4A', '#fff'], 420)}</div>
  <div id="aTitle" class="hl" style="top:1010px;font-size:210px;text-align:center;letter-spacing:-12px"></div>
  <div id="aEth" class="eth abs" style="left:0;right:0;top:1250px;text-align:center;font-size:56px;color:#FF6B4A;letter-spacing:6px">ሙዚቃ</div>
  <div id="aSub" class="hl" style="top:1340px;font-size:84px;text-align:center;letter-spacing:-3.5px;line-height:1.08"></div>
</div>`;

sceneHTML.B = `<div class="sc" id="sB" style="background:linear-gradient(170deg,#3BB7F0,#1C8FD0 60%,#1678B8)">
  <div class="abs" style="right:-180px;top:1100px;font-size:900px;color:rgba(255,255,255,.07);line-height:1"><span class="f">${G('e5bc')}</span></div>
  <div class="hdr"><div class="logo"></div>Musicly</div><div class="kick" style="color:#fff">Telegram</div>
  <div id="bH" class="hl" style="top:200px;font-size:150px"></div>
  <div id="bTg" class="abs" style="left:64px;top:560px;width:952px;height:500px;background:#fff;border-radius:46px;box-shadow:0 40px 90px rgba(0,40,90,.35);color:#111;padding:30px 34px">
    <div style="display:flex;align-items:center;gap:18px"><div style="width:68px;height:68px;border-radius:50%;background:linear-gradient(135deg,#FF8A5B,#C2185B);display:grid;place-items:center;font-weight:800;font-size:30px;color:#fff">S</div><div><div style="font-weight:800;font-size:32px;letter-spacing:-.8px">Selam's channel</div><div style="color:#8a96a3;font-size:22px">2.4K subscribers</div></div></div>
    <div id="bBot" style="margin-top:22px;display:inline-flex;align-items:center;gap:12px;background:#E7F7EE;color:#1E7F4D;font-weight:700;font-size:23px;padding:11px 20px;border-radius:999px"><span class="b" style="font-size:20px">${G('e182')}</span>@selam_music_bot added as admin</div>
    ${[['Addis Nights.mp3', 'Selam Band · 4.8 MB'], ['Slow Rain.mp3', 'Abel K. · 6.1 MB'], ['Golden Hour.m4a', 'Mimi &amp; the Bells · 5.2 MB']].map(([n, m], i) => `<div class="bb" style="margin-top:14px;background:#EAF4FC;border-radius:24px 24px 24px 8px;padding:12px 18px;display:flex;align-items:center;gap:16px;width:640px"><div style="width:58px;height:58px;border-radius:50%;background:#2AABEE;color:#fff;display:grid;place-items:center;font-size:24px"><span class="f">${G('e3d0')}</span></div><div><div style="font-weight:700;font-size:25px;color:#168ACD">${n}</div><div style="color:#7b8794;font-size:19px">${m}</div></div></div>`).join('')}
  </div>
  <div id="bBridge" class="abs" style="left:465px;top:1050px;width:150px;height:150px"><div class="abs" style="inset:0;border-radius:50%;background:#FF6B4A;box-shadow:0 20px 60px rgba(255,107,74,.55)"></div><svg id="bRing" class="abs" style="inset:-14px;width:178px;height:178px" viewBox="0 0 178 178"><circle cx="89" cy="89" r="84" fill="none" stroke="#fff" stroke-width="4" stroke-dasharray="10 14" stroke-linecap="round"/></svg><div class="abs logoW" style="left:38px;top:46px;width:74px;height:58px;display:flex;align-items:center;gap:6px">${[.4, .75, 1, .65, .35].map(h => `<i style="display:block;width:9px;height:${h * 56}px;background:#fff;border-radius:9px"></i>`).join('')}</div></div>
  <div id="bMs" class="abs" style="left:64px;top:1230px;width:952px;height:430px;background:#17181C;border-radius:46px;box-shadow:0 40px 90px rgba(0,0,0,.45);padding:26px 34px">
    <div style="display:flex;justify-content:space-between;align-items:center"><div style="font-weight:800;font-size:32px;letter-spacing:-.8px">Selam's channel</div><div id="bSaved" style="display:flex;align-items:center;gap:10px;background:#1F3B2C;color:#52D08B;font-weight:700;font-size:21px;padding:9px 18px;border-radius:999px"><span class="i" style="font-size:22px">${G('e20c')}</span>Saved to your phone</div></div>
    ${[['a1', 'Addis Nights', 'Selam Band'], ['a3', 'Slow Rain', 'Abel K.'], ['a4', 'Golden Hour', 'Mimi &amp; the Bells']].map(([a, t, s]) => `<div class="mr" style="display:flex;align-items:center;gap:20px;margin-top:20px"><div class="art ${a}" style="width:78px;height:78px;border-radius:17px"></div><div><div style="font-size:30px;font-weight:700">${t}</div><div style="font-size:21px;color:#8A8FA3;margin-top:3px">${s}</div></div><span class="f ck" style="margin-left:auto;color:#30A46C;font-size:38px">${G('e184')}</span></div>`).join('')}
  </div></div>`;

sceneHTML.C = `<div class="sc" id="sC" style="background:#F3EEE6;color:#1C1D22">
  <div class="abs" style="left:-260px;top:520px;width:760px;height:760px;border-radius:50%;background:radial-gradient(circle,rgba(255,107,74,.30),transparent 68%)"></div><div class="abs" style="right:-300px;top:900px;width:800px;height:800px;border-radius:50%;background:radial-gradient(circle,rgba(183,168,255,.45),transparent 68%)"></div>
  <div class="hdr" style="color:#1C1D22"><div class="logo"></div>Musicly</div>
  <div id="cK1" class="hl" style="top:196px;font-size:132px;color:#1C1D22"></div>
  <div id="cK2" class="hl" style="top:196px;font-size:132px;color:#1C1D22"></div>
  <div id="cK3" class="hl" style="top:196px;font-size:132px;color:#1C1D22"></div>
  ${place('cPh', 169, 470, 1.75, 0, phone('cPhone', `<div class="pane" id="cP1">${homePane()}</div><div class="pane" id="cP2">${radioPane()}</div><div class="pane" id="cP3">${lyricsPane()}</div>`, 700))}</div>`;

sceneHTML.D = `<div class="sc" id="sD" style="background:linear-gradient(170deg,#FFE9DA,#FFD0B8 60%,#FFC0A6);color:#1C1D22">
  <div class="hdr" style="color:#1C1D22"><div class="logo"></div>Musicly</div>
  <div id="dK1" class="hl" style="top:196px;font-size:132px;color:#1C1D22"></div>
  <div id="dK2" class="hl" style="top:196px;font-size:132px;color:#1C1D22"></div>
  ${place('dPh', 169, 470, 1.75, 0, phone('dPhone', `<div class="pane" id="dP1">${lockPane()}</div><div class="pane" id="dP2">${homeWPane()}</div><div class="fing" id="dFing" style="left:300px;top:640px"></div><div class="rip" id="dRip" style="left:300px;top:640px"></div>`, 700))}
</div>`;

const jamPhone = (id, title, sub, extra) => phone(id, `<div class="pane">${jamBody(title, sub, extra)}</div>`, 760);
sceneHTML.J = `<div class="sc" id="sJ" style="background:linear-gradient(180deg,#CBBFFF,#A592FF 55%,#8C77FF)">
  <div id="jIntro">
    <div id="jRings" class="abs" style="left:240px;top:560px;width:600px;height:600px">${[0, 1, 2, 3].map(i => `<div class="ring abs" style="inset:0;border-radius:50%;border:6px solid rgba(255,255,255,.75)"></div>`).join('')}</div>
    <div id="jIcon" class="abs" style="left:390px;top:710px;width:300px;height:300px;border-radius:50%;background:#1C1D22;display:grid;place-items:center;font-size:170px;color:#fff;box-shadow:0 40px 90px rgba(40,20,120,.5)"><span class="f">${G('e68e')}</span></div>
    <div id="jWord" class="hl" style="top:1130px;text-align:center;font-size:380px;color:#fff;letter-spacing:-8px;line-height:1.05"></div>
  </div>
  <div class="hdr" style="color:#1C1D22"><div class="logo"></div>Musicly</div><div class="kick" style="color:#3a2f7a">Jam</div>
  <div id="jH" class="hl" style="top:180px;font-size:132px;color:#1C1D22"></div>
  <div id="jTicket" class="abs" style="left:64px;top:372px;width:952px;height:170px;background:#1C1D22;border-radius:34px;box-shadow:0 36px 80px rgba(40,20,120,.45);display:flex;align-items:center">
    <div style="padding:0 40px;border-right:3px dashed rgba(255,255,255,.28);height:100%;display:flex;flex-direction:column;justify-content:center;width:640px"><div style="font-size:22px;letter-spacing:5px;color:#FF6B4A;font-weight:800">JAM CODE</div><div id="jCode" class="mono" style="font-size:62px;letter-spacing:2px;margin-top:6px;white-space:nowrap">&nbsp;</div></div>
    <div style="flex:1;text-align:center;font-weight:800;font-size:30px;line-height:1.25">Share it.<br><span style="color:#B7A8FF">Friends tap Join.</span></div></div>
  ${place('jPl', -22, 780, 1.0, -8, jamPhone('jPhL', 'Kidus\'s Jam', 'Online · 3 people', ''))}
  ${place('jPr', 690, 780, 1.0, 8, jamPhone('jPhR', 'Kidus\'s Jam', 'Online · 3 people', ''))}
  <div id="jTagL" class="abs" style="left:36px;top:588px"><span class="pill light" style="font-size:28px;padding:12px 24px 12px 12px;gap:12px"><span class="av" style="width:46px;height:46px;border-radius:50%;background:#2BB673;color:#fff;display:grid;place-items:center;font-weight:800;font-size:24px">R</span>Robi joined <span style="color:#8A8FA3;font-weight:500">Addis Ababa</span></span></div>
  <div id="jTagR" class="abs" style="right:36px;top:588px"><span class="pill light" style="font-size:28px;padding:12px 24px 12px 12px;gap:12px"><span class="av" style="width:46px;height:46px;border-radius:50%;background:#8E7CFF;color:#fff;display:grid;place-items:center;font-weight:800;font-size:24px">S</span>Sara joined <span style="color:#8A8FA3;font-weight:500">Berlin</span></span></div>
  ${place('jPm', 245, 640, 1.3, 0, `<div id="jHostWrap" style="position:relative">${phone('jPhM', `<div class="pane">${jamBody("Kidus's Jam", `<span id="jPeople">You're hosting · 1 person</span>`, jamCtl)}</div>`, 760)}</div>`)}
  <div id="jSync" class="abs" style="left:0;right:0;top:1612px;text-align:center"><span class="pill dark" style="font-size:36px"><span id="jSyncDot" style="width:20px;height:20px;border-radius:50%;background:#52D08B;display:inline-block"></span>All in sync</span></div></div>`;

sceneHTML.G = `<div class="sc" id="sG" style="background:radial-gradient(900px 700px at 80% 20%,rgba(142,124,255,.28),transparent 70%),radial-gradient(800px 600px at 10% 90%,rgba(255,107,74,.22),transparent 70%),#0E0F12">
  <div class="hdr"><div class="logo"></div>Musicly</div><div class="kick" style="color:#B7A8FF">Jam</div>
  <div id="gH1" class="hl" style="top:190px;font-size:150px"></div>
  <div id="gH2" class="hl" style="top:190px;font-size:150px"></div>
  ${place('gPh', 201, 500, 1.6, 0, phone('gPhone', `
    <div class="pane" id="gP1">${stat()}
      <div style="padding:14px 22px 0"><div style="font-weight:800;font-size:25px;letter-spacing:-.9px">Kidus's Jam</div><div class="g" style="margin-top:2px;font-size:13px">Online · Hosted by Kidus · 3 people</div></div>
      <div style="margin:14px 18px 0;background:#1C1D22;border-radius:22px;padding:14px"><div style="display:flex;gap:13px;align-items:center"><div class="art a1" style="width:62px;height:62px;border-radius:14px"></div><div><div style="font-weight:800;font-size:17px">Addis Nights</div><div class="g" style="font-size:12px">Selam Band</div></div><div class="eq" style="margin-left:auto"><i></i><i></i><i></i><i></i></div></div>
        <div style="height:4px;border-radius:4px;background:#2C2E35;margin-top:13px;position:relative"><i id="gBar" style="position:absolute;left:0;top:0;bottom:0;width:40%;background:#fff;border-radius:4px"></i></div></div>
      <div class="sec" style="padding-top:18px">Up next</div>
      <div id="gQ1" class="song" style="height:70px">${rows([['a6', 'Lose Yourself', 'Eminem']]).replace(/^<div class="song">|<\/div>$/g, '')}</div>
      <div id="gQ2" class="song" style="height:70px;position:relative"><div id="gQ2g" class="abs" style="inset:2px 12px;border-radius:16px;background:rgba(255,107,74,.2)"></div><div class="art a4"></div><div><div class="t">Golden Hour</div><div class="s">Mimi &amp; the Bells · <span style="color:#FF8A6B">Added by Sara</span></div></div></div>
      <div id="gQ3" class="song" style="height:70px;position:relative"><div id="gQ3g" class="abs" style="inset:2px 12px;border-radius:16px;background:rgba(142,124,255,.25)"></div><div class="art a3"></div><div><div class="t">Slow Rain</div><div class="s">Abel K. · <span style="color:#B7A8FF">Sent from Sara's phone</span></div></div></div>
      <div id="gAdd" class="abs" style="left:18px;right:18px;top:622px;height:60px;border-radius:20px;background:#fff;color:#1C1D22;display:flex;align-items:center;justify-content:center;gap:9px;font-weight:800;font-size:18px"><span class="b" style="font-size:20px">${G('e3d4')}</span>Add songs</div>
      <div id="gToast" class="toast" style="top:34px"><div class="d"><span class="b">${G('e182')}</span></div><span id="gToastT">Sara added Golden Hour</span></div>
      <div id="gSheet" class="abs" style="left:0;right:0;top:150px;bottom:0;background:#1C1D22;border-radius:30px 30px 0 0;box-shadow:0 -20px 50px rgba(0,0,0,.5)">
        <div style="width:44px;height:5px;border-radius:5px;background:#3a3c45;margin:10px auto 0"></div>
        <div style="margin:14px 18px 0;background:#2C2E35;border-radius:14px;height:42px;display:flex;position:relative;font-weight:700;font-size:14px"><div id="gSeg" class="abs" style="left:4px;top:4px;width:182px;height:34px;border-radius:11px;background:#fff"></div><div style="flex:1;display:grid;place-items:center;position:relative;z-index:2;color:#1C1D22" id="gSegA">Kidus's songs</div><div style="flex:1;display:grid;place-items:center;position:relative;z-index:2" id="gSegB">My songs</div></div>
        <div style="margin:12px 18px 0;background:#2C2E35;border-radius:14px;height:42px;display:flex;align-items:center;gap:9px;padding:0 14px;color:#8A8FA3;font-size:14px"><span class="i" style="font-size:18px">${G('e30c')}</span>Search songs or artists</div>
        <div id="gListH" class="abs" style="left:0;right:0;top:128px">${[['a1', 'Addis Nights', 'Selam Band'], ['a4', 'Golden Hour', 'Mimi &amp; the Bells'], ['a2', 'Lagos Nights', 'Burna Beats'], ['a5', 'Tokyo Drift', 'City Pop Club']].map(([a, t, s], i) => `<div class="song gr" style="height:76px"><div class="art ${a}"></div><div><div class="t">${t}</div><div class="s">${s}</div></div><span class="b gck" style="margin-left:auto;font-size:20px;color:#52D08B;opacity:0">${G('e182')}</span><span class="b gplus" style="margin-left:auto;font-size:20px;color:#8A8FA3;${i === 1 ? 'display:none' : ''}">${G('e3d4')}</span></div>`).join('')}</div>
        <div id="gListM" class="abs" style="left:0;right:0;top:128px">${[['a3', 'Slow Rain', 'Abel K.'], ['a6', 'Tizita Blue', 'Hanna M.'], ['a1', 'Morning Light', 'Selam Band']].map(([a, t, s], i) => `<div class="song" style="height:76px"><div class="art ${a}"></div><div><div class="t">${t}</div><div class="s">${s}</div></div>${i === 0 ? `<svg id="gUp" width="34" height="34" viewBox="0 0 34 34" style="margin-left:auto"><circle cx="17" cy="17" r="13" fill="none" stroke="#3a3c45" stroke-width="4"/><circle id="gUpC" cx="17" cy="17" r="13" fill="none" stroke="#B7A8FF" stroke-width="4" stroke-linecap="round" stroke-dasharray="82" stroke-dashoffset="82" transform="rotate(-90 17 17)"/></svg>` : ''}</div>`).join('')}</div>
      </div>
      <div class="fing" id="gFing" style="left:200px;top:700px"></div><div class="rip" id="gRip" style="left:200px;top:700px"></div></div>
    <div class="pane" id="gP2">${stat()}
      <div style="padding:14px 22px 0"><div style="font-weight:800;font-size:25px;letter-spacing:-.9px">Kidus's Jam</div><div id="gHost" class="g" style="margin-top:2px;font-size:13px">You're hosting · 3 people</div></div>
      <div style="display:flex;gap:9px;flex-wrap:wrap;padding:20px 22px 0"><span class="chipd" style="background:#24252B"><span class="f" style="margin-right:6px;font-size:14px;color:#FFC857">${G('e614')}</span>Kidus (you)</span><span id="gChipR" class="chipd" style="background:#24252B;gap:8px">Robi <span class="b" style="font-size:12px;color:#8A8FA3">${G('e4f6')}</span></span><span class="chipd" style="background:#24252B;gap:8px">Sara <span class="b" style="font-size:12px;color:#8A8FA3">${G('e4f6')}</span></span></div>
      <div style="display:flex;align-items:center;padding:22px 22px 0"><div><div style="font-weight:700;font-size:17px">Friends can control playback</div><div class="g" style="font-size:12.5px;margin-top:3px">Play, pause, skip and seek</div></div><div id="gSw" style="margin-left:auto;width:58px;height:34px;border-radius:34px;background:#3a3c45;position:relative"><i id="gSwK" style="position:absolute;left:4px;top:4px;width:26px;height:26px;border-radius:50%;background:#fff;display:block"></i></div></div>
      <div style="margin:26px 18px 0;background:#1C1D22;border-radius:22px;padding:16px"><div style="display:flex;gap:13px;align-items:center"><div class="art a1" style="width:62px;height:62px;border-radius:14px"></div><div><div style="font-weight:800;font-size:17px">Addis Nights</div><div class="g" style="font-size:12px">Selam Band</div></div></div>
        <div id="gGuestCtl" style="display:flex;justify-content:center;gap:34px;align-items:center;margin-top:16px;font-size:24px;color:#B7A8FF"><span class="f">${G('e5a4')}</span><span class="f" style="font-size:30px">${G('e39e')}</span><span class="f">${G('e5a6')}</span></div>
        <div id="gGuestNote" class="g" style="text-align:center;margin-top:8px;font-size:12px">Robi &amp; Sara can control playback</div></div>
      <div id="gToast2" class="toast" style="top:34px"><div class="d" style="background:#E5484D"><span class="b">${G('e4f6')}</span></div><span>Robi removed from the Jam</span></div>
      <div class="fing" id="gFing2" style="left:200px;top:700px"></div><div class="rip" id="gRip2" style="left:200px;top:700px"></div></div>`, 700))}
  <div id="gTagA" class="abs" style="left:64px;top:372px"><span class="pill light"><span class="i" style="font-size:36px">${G('e2a6')}</span>From the host's library</span></div>
  <div id="gTagB" class="abs" style="left:64px;top:372px"><span class="pill accent"><span class="i" style="font-size:36px">${G('e1e0')}</span>From their phone</span></div></div>`;

sceneHTML.H = `<div class="sc" id="sH" style="background:linear-gradient(170deg,#E3F6EC,#BFE9D6 55%,#9ED9C0);color:#1C1D22">
  <div class="hdr" style="color:#1C1D22"><div class="logo"></div>Musicly</div><div class="kick" style="color:#1E7F4D">Same room</div>
  <div id="hH" class="hl" style="top:190px;font-size:150px;color:#1C1D22"></div>
  <svg id="hRings" class="abs" style="left:0;top:560px;width:1080px;height:1000px" viewBox="0 0 1080 1000">
    ${[0, 1, 2, 3].map(i => `<circle class="hr" cx="540" cy="450" r="150" fill="none" stroke="#1E7F4D" stroke-width="5" opacity="0"/>`).join('')}
    ${[[540, 110, 0], [880, 450, 1], [540, 790, 2], [200, 450, 3]].map(([x, y]) => `<line class="hl2" x1="540" y1="450" x2="${x}" y2="${y}" stroke="#1E7F4D" stroke-opacity=".55" stroke-width="5" stroke-dasharray="3 16" stroke-linecap="round"/>`).join('')}</svg>
  <div id="hCenter" class="abs" style="left:420px;top:890px;width:240px;height:240px;border-radius:50%;background:#1C1D22;display:grid;place-items:center;font-size:120px;color:#fff;box-shadow:0 30px 70px rgba(30,127,77,.4)"><span class="i">${G('e4ea')}</span></div>
  ${[['K', '#FF6B4A', 'Kidus', 540 - 130, 560 + 110 - 100, 0], ['R', '#2BB673', 'Robi', 880 - 130, 560 + 450 - 100, 1], ['S', '#8E7CFF', 'Sara', 540 - 130, 560 + 790 - 100, 2], ['A', '#F7C948', 'Abel', 200 - 130, 560 + 450 - 100, 3]].map(([c, col, n, x, y], i) => `<div class="hp abs" style="left:${x}px;top:${y}px;width:260px;text-align:center"><div style="width:130px;height:200px;margin:0 auto;border-radius:34px;background:#050506;padding:7px;box-shadow:0 24px 50px rgba(0,0,0,.35)"><div style="width:100%;height:100%;border-radius:28px;background:linear-gradient(160deg,${col},#1C1D22);display:grid;place-items:center;font-weight:800;font-size:54px;color:#fff">${c}</div></div><div style="margin-top:14px;font-weight:800;font-size:34px">${n}</div></div>`).join('')}
  <div id="hChip" class="abs" style="left:0;right:0;top:1580px;text-align:center"><span class="pill dark"><span class="i" style="font-size:36px">${G('e4ea')}</span>Same Wi-Fi or hotspot</span></div></div>`;

sceneHTML.I = `<div class="sc" id="sI" style="background:#0E0F12">
  <div id="iGlow" class="abs" style="left:90px;top:200px;width:900px;height:900px;border-radius:50%;background:radial-gradient(circle,rgba(255,107,74,.38),rgba(183,168,255,.12) 45%,transparent 68%)"></div>
  <div id="iBars" class="abs" style="left:0;width:1080px;top:300px;height:300px;display:flex;justify-content:center;align-items:center;gap:22px">${bars([.32, .55, .82, 1, .7, .46, .26], 40, 22, ['#FF6B4A', '#B7A8FF', '#fff', '#fff', '#B7A8FF', '#FF6B4A', '#fff'], 300)}</div>
  <div id="iTitle" class="hl" style="top:660px;font-size:190px;text-align:center;letter-spacing:-10px"></div>
  <div id="iTag" class="hl" style="top:880px;font-size:112px;text-align:center;letter-spacing:-4px"></div>
  <div id="iChips" class="abs" style="left:64px;right:64px;top:1130px;display:flex;flex-wrap:wrap;gap:16px;justify-content:center">${[['e5bc', 'Telegram'], ['e68e', 'Jam'], ['e75c', 'Lyrics'], ['e77e', 'Live radio'], ['e1e0', 'Widgets']].map(([g, n]) => `<span class="pill light" style="font-size:26px;padding:14px 22px;gap:12px"><span class="f" style="font-size:26px">${G(g)}</span>${n}</span>`).join('')}</div>
  <div id="iBtn" class="abs" style="left:0;right:0;top:1330px;text-align:center"><span class="pill accent" style="font-size:44px;padding:30px 56px;gap:20px"><span class="b" style="font-size:42px">${G('e20c')}</span>Download on GitHub</span></div>
  <div id="iUrl" class="mono abs" style="left:0;right:0;top:1500px;text-align:center;font-size:32px;color:#8A8FA3;letter-spacing:.5px">github.com/Kidyoh/Musicly</div></div>`;

for (const k of ['A', 'B', 'C', 'D', 'J', 'G', 'H', 'I']) stage.insertAdjacentHTML('beforeend', sceneHTML[k]);
stage.insertAdjacentHTML('beforeend', '<div id="cap"><span></span></div>');
// scrub waveforms inside phones
document.querySelectorAll('.scr').forEach(s => (s.dataset.done = 1));
