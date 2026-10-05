// node render.js <outDir> <fps> [shard shards] | node render.js --still t1,t2,... <outDir>
const { chromium } = require('playwright');
const path = require('path'), fs = require('fs');
(async () => {
  const args = process.argv.slice(2);
  const still = args[0] === '--still';
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--hide-scrollbars'] });
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => console.log('PAGEERR', String(e).slice(0, 400)));
  page.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', m.text().slice(0, 300)); });
  await page.goto('file://' + path.resolve(__dirname, 'src/index.html'), { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => window.READY === true, null, { timeout: 15000 });
  await page.waitForTimeout(500);
  if (still) {
    const times = args[1].split(',').map(Number), out = args[2];
    fs.mkdirSync(out, { recursive: true });
    for (const t of times) { await page.evaluate(t => window.seek(t), t); await page.screenshot({ path: `${out}/t${String(t).replace('.', '_')}.png` }); }
    if (args[3] === 'sfx') fs.writeFileSync(out + '/sfx.json', JSON.stringify(await page.evaluate(() => ({ sfx: window.SFX, total: window.TOTAL })), null, 1));
  } else {
    const [out, fps, shard = 0, shards = 1] = [args[0], +args[1], +(args[2] ?? 0), +(args[3] ?? 1)];
    const total = await page.evaluate(() => window.TOTAL), n = Math.ceil(total * fps);
    fs.mkdirSync(out, { recursive: true });
    if (shard === 0) fs.writeFileSync(path.resolve(__dirname, 'sfx.json'), JSON.stringify(await page.evaluate(() => ({ sfx: window.SFX, total: window.TOTAL })), null, 1));
    for (let f = shard; f < n; f += shards) {
      await page.evaluate(t => window.seek(t), f / fps);
      await page.screenshot({ path: `${out}/f${String(f).padStart(5, '0')}.jpg`, type: 'jpeg', quality: 93 });
      if (f % (shards * 30) === shard) console.log(`shard ${shard}: frame ${f}/${n}`);
    }
  }
  await browser.close();
})();
