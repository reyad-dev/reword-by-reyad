// Run: node extension/tests/credential-storage.browser.cjs (requires Playwright Chromium).
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const extension = path.resolve(process.env.REWORD_EXTENSION_PATH || path.join(__dirname, '..'));
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'reword-save-check-'));
const password = 'integration-master-password';
const form = '<form><input autocomplete="username"><input type="email"><input type="password"><button>Log in</button></form>';

async function launch() {
  return chromium.launchPersistentContext(profile, {
    channel: 'chromium',
    headless: true,
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`]
  });
}

async function popup(context) {
  const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
  const id = new URL(worker.url()).host;
  const page = await context.newPage();
  await page.goto(`chrome-extension://${id}/popup.html`);
  return page;
}

const send = (page, message) => page.evaluate(message => chrome.runtime.sendMessage(message), message);

async function loginPage(context, user, email, pass) {
  const page = await context.newPage();
  await page.route('http://social.test/**', route => route.fulfill({ contentType: 'text/html', body: form }));
  await page.goto('http://social.test/login');
  await page.locator('.reword-autofill-btn').first().waitFor();
  await page.locator('[autocomplete=username]').fill(user);
  await page.locator('[type=email]').fill(email);
  await page.locator('[type=password]').fill(pass);
  return page;
}

async function confirmSave(context, page, expectedLabel = 'حفظ') {
  await page.locator('.reword-save-host').waitFor();
  // CDP can inspect a closed shadow tree without changing the production prompt.
  const cdp = await context.newCDPSession(page);
  try {
    const { root } = await cdp.send('DOM.getDocument', { depth: -1, pierce: true });
    function find(node, predicate) {
      if (predicate(node)) return node;
      for (const child of [...(node.children || []), ...(node.shadowRoots || [])]) {
        const result = find(child, predicate);
        if (result) return result;
      }
    }
    const host = find(root, node => node.attributes?.includes('reword-save-host'));
    assert.ok(host);
    const button = find(host, node => node.nodeName === 'BUTTON');
    assert.equal(button.children?.[0]?.nodeValue, expectedLabel);
    const { model } = await cdp.send('DOM.getBoxModel', { nodeId: button.nodeId });
    const points = model.content;
    await page.mouse.click((points[0] + points[4]) / 2, (points[1] + points[5]) / 2);
    await page.locator('.reword-save-host').waitFor({ state: 'detached' });
  } finally { await cdp.detach(); }
}

(async () => {
  let context;
  try {
    context = await launch();
    let vaultPage = await popup(context);
    assert.equal((await send(vaultPage, { action: 'SETUP_NEW_VAULT', password })).success, true);
    const first = await loginPage(context, 'alice', 'alice@mail.test', 'alice-test-password');
    await confirmSave(context, first);
    const stored = await vaultPage.evaluate(() => chrome.storage.local.get('encVault'));
    assert.ok(stored.encVault);
    assert.equal(stored.encVault.includes('alice-test-password'), false);
    await context.close();

    context = await launch();
    vaultPage = await popup(context);
    assert.equal((await send(vaultPage, { action: 'GET_STATUS' })).isUnlocked, false);
    assert.equal((await send(vaultPage, { action: 'UNLOCK_VAULT', password })).success, true);
    const restored = await send(vaultPage, { action: 'GET_ALL_SITES' });
    assert.equal(restored.sites[0].url, 'http://social.test');
    assert.equal(restored.sites[0].accounts[0].user, 'alice');
    assert.equal(restored.sites[0].accounts[0].email, 'alice@mail.test');
    assert.equal(restored.sites[0].accounts[0].pass, 'alice-test-password');

    for (const locked of [false, true]) {
      if (locked) await send(vaultPage, { action: 'LOCK_VAULT' });
      const saved = await loginPage(context, 'alice', 'alice@mail.test', 'alice-test-password');
      await saved.waitForTimeout(1100);
      assert.equal(await saved.locator('.reword-save-host').count(), 0);
      await saved.close();
    }
    await send(vaultPage, { action: 'UNLOCK_VAULT', password });
    const second = await loginPage(context, 'bob', 'bob@mail.test', 'bob-test-password');
    await confirmSave(context, second);
    const result = await send(vaultPage, { action: 'GET_ALL_SITES' });
    assert.equal(result.sites.length, 1);
    assert.deepEqual(result.sites[0].accounts.map(account => account.user), ['alice', 'bob']);
    assert.equal(result.sites[0].accounts[0].pass, 'alice-test-password');
    const accountId = result.sites[0].accounts[0].id;
    const changed = await loginPage(context, 'alice', 'alice@mail.test', 'alice-updated-password');
    await confirmSave(context, changed, 'تحديث');
    const updated = await send(vaultPage, { action: 'GET_ALL_SITES' });
    assert.equal(updated.sites[0].accounts.length, 2);
    assert.equal(updated.sites[0].accounts[0].id, accountId);
    assert.equal(updated.sites[0].accounts[0].pass, 'alice-updated-password');
    assert.equal(updated.sites[0].accounts[1].pass, 'bob-test-password');
    console.log('PASS: real extension Save/Update clicks, encrypted Chrome disk storage, full browser restart, username/email/password restore, duplicate suppression unlocked/locked, separate new account, and existing password update.');
  } finally {
    await context?.close();
    const resolvedProfile = path.resolve(profile);
    if (path.dirname(resolvedProfile) === path.resolve(os.tmpdir()) && path.basename(resolvedProfile).startsWith('reword-save-check-')) {
      fs.rmSync(resolvedProfile, { recursive: true, force: true });
    }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
