const { chromium } = require('playwright');
const fs = require('node:fs');
const http = require('node:http');
const assert = require('node:assert/strict');
const path = require('node:path');
const extension = path.resolve('extension');
const server = http.createServer((req, res) => {
  const file = path.join(extension, req.url.split('?')[0]);
  res.setHeader('Content-Security-Policy', "script-src 'self'; object-src 'self'");
  res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  try { res.end(fs.readFileSync(file)); } catch { res.statusCode = 404; res.end(); }
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', msg => { if (/violates.*Content Security Policy/i.test(msg.text())) errors.push(msg.text()); });
    await page.route('https://www.google.com/**', route => route.abort());
    await page.addInitScript(() => {
      window.chrome = {
        runtime: { lastError: null, getManifest: () => ({ version: '1.3.3' }), sendMessage(msg, cb) {
          if (msg.action === 'GET_STATUS') cb?.({ isUnlocked: true });
          else if (msg.action === 'GET_ALL_SITES') cb?.({ sites: [{ name: 'Example', url: 'https://example.test', accounts: [{ user: 'alice', pass: 'test' }] }] });
          else if (msg.action === 'SYNC_NOW') cb?.({ success: true });
          else cb?.({ matches: window.testMatches || [] });
        } },
        tabs: { async query() { return [{ id: 1, url: 'https://example.test' }]; } },
        storage: { local: { async get() { return {}; } } }
      };
      document.addEventListener('securitypolicyviolation', event => { throw new Error(event.violatedDirective); });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/popup.html`);
    await page.locator('#btn-quick-add-cur').click();
    await page.waitForFunction(() => document.activeElement.id === 'add-user');
    await page.waitForFunction(() => document.querySelector('.site-fav-img')?.style.display === 'none');
    await page.locator('#btn-close-modal').click();
    await page.evaluate(() => { window.testMatches = [{ siteName: 'Example', siteUrl: 'https://example.test', user: 'alice', pass: 'test' }]; });
    await page.locator('#btn-sync-now').click();
    await page.waitForFunction(() => document.querySelector('#current-site-matches .site-fav-img')?.style.display === 'none');
    assert.deepEqual(errors, []);
    console.log('PASS: Chrome popup under strict CSP, quick-add focus, both favicon fallbacks; no runtime/CSP errors.');

    const scroll = await browser.newPage();
    await scroll.setContent('<style>body{height:3000px;margin:0}#panel{position:absolute;top:350px;left:100px;height:240px;width:400px;overflow:auto}form{height:1000px;padding-top:100px}input{height:40px;width:300px}</style><div id="panel"><form><input type="email"></form></div>');
    await scroll.addStyleTag({ path: path.join(extension, 'content.css') });
    await scroll.evaluate(() => { window.chrome = { runtime: { lastError: null, onMessage: { addListener() {} }, sendMessage(msg, cb) { cb?.({ pending: false }); } } }; });
    await scroll.addScriptTag({ path: path.join(extension, 'content.js') });
    async function check() {
      const geometry = await scroll.evaluate(() => {
        const field = document.querySelector('input').getBoundingClientRect();
        const badge = document.querySelector('.reword-autofill-btn').getBoundingClientRect();
        const transition = getComputedStyle(document.querySelector('.reword-autofill-btn')).transitionProperty;
        return { delta: Math.abs((field.top + field.height / 2) - (badge.top + badge.height / 2)), transition };
      });
      assert.ok(geometry.delta < 1, JSON.stringify(geometry));
      assert.ok(!/\b(all|top|left)\b/.test(geometry.transition));
    }
    await check();
    for (const y of [30, 70, 100]) {
      await scroll.evaluate(y => window.scrollTo(0, y), y);
      await scroll.waitForTimeout(40);
      await check();
    }
    await scroll.evaluate(() => document.querySelector('#panel').scrollTop = 50);
    await scroll.waitForTimeout(40);
    await check();
    await scroll.evaluate(() => document.querySelector('#panel').scrollTop = 180);
    await scroll.waitForTimeout(40);
    assert.equal(await scroll.locator('.reword-autofill-btn').evaluate(el => getComputedStyle(el).display), 'none');
    await scroll.evaluate(() => document.querySelector('#panel').scrollTop = 0);
    await scroll.waitForTimeout(40);
    await check();
    await scroll.evaluate(() => document.querySelector('input').style.direction = 'rtl');
    await scroll.waitForTimeout(150);
    const delta = await scroll.evaluate(() => document.querySelector('.reword-autofill-btn').getBoundingClientRect().left - document.querySelector('input').getBoundingClientRect().left);
    assert.equal(delta, 6);
    console.log('PASS: Chrome page/container scroll alignment, clipping/reappearance, RTL; geometry is never animated.');
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
