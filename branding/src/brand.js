// Musicly logo: the app icon's seven-bar waveform.
const LOGO = `<svg viewBox="0 0 100 100"><g fill="#fff">${[[24,40,22],[34,30,42],[44,18,64],[54,12,76],[64,24,52],[74,34,32],[84,42,16]]
  .map(([x, y, h]) => `<rect x="${x - 4}" y="${y}" width="8" height="${h}" rx="4"/>`).join('')}</g></svg>`;
document.querySelectorAll('.logo').forEach(e => (e.innerHTML = LOGO));

// Waveform glyphs inside artwork tiles.
document.querySelectorAll('.art').forEach((a, k) => {
  if (a.dataset.plain !== undefined) return;
  const h = [0.35, 0.7, 1, 0.6, 0.85, 0.45];
  a.innerHTML += `<div class="wv">${h.map((v, i) => `<i style="height:${Math.round(100 * (0.4 + 0.6 * Math.abs(Math.sin(k + i * 1.3))) * v)}%"></i>`).join('')}</div>`;
});

// Scrubber waveforms: <div class="scrub" data-n="56" data-at="0.45">
document.querySelectorAll('.scrub').forEach(s => {
  const n = +s.dataset.n || 56, at = +s.dataset.at || 0.5;
  let html = '';
  for (let i = 0; i < n; i++) {
    const v = 0.25 + 0.75 * Math.abs(Math.sin(i * 0.55) * Math.cos(i * 0.21));
    html += `<i style="height:${Math.round(v * 100)}%;opacity:${i / n < at ? 1 : 0.28}"></i>`;
  }
  s.innerHTML = html;
});
