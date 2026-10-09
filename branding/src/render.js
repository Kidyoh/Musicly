const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const files = process.argv.slice(2);
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'], proxy: process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY } : undefined });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1280 }, deviceScaleFactor: 2, ignoreHTTPSErrors: true });
  for (const f of files) {
    await page.goto('file://' + path.resolve(f), { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(600);
    const out = path.resolve('..', path.basename(f).replace('.html', '.png'));
    await page.screenshot({ path: out });
    console.log('saved', out, await page.evaluate(() => document.fonts.check('20px "Noto Sans Ethiopic"', 'ሙ')));
  }
  await browser.close();
})();
