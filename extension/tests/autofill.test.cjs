// Run: npm install --no-save jsdom@26 && node --test extension/tests/autofill.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const source = file => fs.readFileSync(path.join(__dirname, '..', file), 'utf8');

// Load crypto.js (an ES module) as a plain script to reuse its pure helpers.
const cryptoHelpers = (() => {
  const ctx = { URL, crypto: globalThis.crypto, TextEncoder, TextDecoder, btoa, atob };
  vm.runInNewContext(source('crypto.js').replace(/^export /gm, '') +
    '\n;Object.assign(this, { CV, cleanWorkerUrl, extractDomain, siteMatchesDomain, sameUser, hostMatches, accountMatchesCredential, accountContainsCredential });', ctx);
  return ctx;
})();

test('domain matching is strict: subdomains and parents only, no substrings, siblings or brand names', () => {
  const m = cryptoHelpers.siteMatchesDomain;
  assert.equal(m({ url: 'https://x.com' }, 'netflix.com'), false);
  assert.equal(m({ url: 'https://example.test' }, 'login.example.test'), true);
  assert.equal(m({ url: 'https://login.example.test' }, 'example.test'), true);
  assert.equal(m({ url: 'https://a.example.test' }, 'b.example.test'), false);
  assert.equal(m({ url: 'https://alice.github.io' }, 'github.io'), false);
  assert.equal(m({ url: 'https://example.test' }, 'example.test.attacker.test'), false);
  assert.equal(m({ url: 'a.test, https://b.example.org/login' }, 'b.example.org'), true);
  assert.equal(m({ name: 'facebook.com', url: '' }, 'm.facebook.com'), true);
  assert.equal(m({ name: 'Facebook', url: '' }, 'facebook.xyz'), false);
  assert.equal(m({ name: '', url: '' }, 'any.test'), false);
  assert.equal(cryptoHelpers.sameUser(' Alice@Test ', 'alice@test'), true);
});

function worker({ local = {}, session = {}, realCrypto = false } = {}) {
  const area = data => ({
    async get(keys) { return keys === null ? { ...data } : Object.fromEntries((typeof keys === 'string' ? [keys] : keys).map(k => [k, data[k]])); },
    async set(value) { Object.assign(data, value); },
    async remove(keys) { for (const key of Array.isArray(keys) ? keys : [keys]) delete data[key]; },
    async clear() { for (const key of Object.keys(data)) delete data[key]; }
  });
  let listener;
  const chrome = {
    storage: { local: area(local), session: area(session) },
    alarms: { onAlarm: { addListener() {} }, create() {}, async clear() {} },
    tabs: { onRemoved: { addListener() {} }, query(q, callback) { callback([]); } },
    runtime: { onMessage: { addListener(fn) { listener = fn; } }, onInstalled: { addListener() {} } }
  };
  vm.runInNewContext(source('background.js').replace(/^import .*;$/m, ''), {
    chrome, console, structuredClone, URL, Date, Math, crypto: globalThis.crypto, TextEncoder,
    CV: realCrypto ? cryptoHelpers.CV : { enc: async value => JSON.stringify(value), dec: async value => JSON.parse(value) },
    cleanWorkerUrl: cryptoHelpers.cleanWorkerUrl, extractDomain: cryptoHelpers.extractDomain,
    // Real matching helpers from crypto.js (the same code the extension ships)
    siteMatchesDomain: cryptoHelpers.siteMatchesDomain, sameUser: cryptoHelpers.sameUser,
    hostMatches: cryptoHelpers.hostMatches, accountMatchesCredential: cryptoHelpers.accountMatchesCredential,
    accountContainsCredential: cryptoHelpers.accountContainsCredential
  });
  const sender = { tab: { id: 1 }, frameId: 0, url: 'https://example.test/signup' };
  return { local, session, sender, send: (msg, from = sender) => new Promise(resolve => listener(msg, from, resolve)) };
}

test('capture waits for consent; confirmation saves, updates and preserves metadata', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test', hint: 'keep-me' }, {});
  const pending = await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice@test', pass: 'first-test-password' });
  assert.equal(pending.pending, true);
  assert.equal(JSON.parse(w.local.encVault).sites.length, 0);
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).success, true);
  assert.equal(JSON.parse(w.local.encVault).meta.hint, 'keep-me');
  assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice@test', pass: 'first-test-password' })).pending, false);
  assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice@test', pass: 'changed-test-password' })).update, true);
  await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' });
  const saved = JSON.parse(w.local.encVault).sites[0].accounts;
  assert.equal(saved.length, 1);
  assert.equal(saved[0].pass, 'changed-test-password');
});

test('locked vault suppresses saved credentials on hosts containing s and multi-URL sites', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  await w.send({ action: 'SAVE_CREDENTIAL', siteName: 'Social', siteUrl: 'https://social.test, https://second.test',
    user: 'Alice', email: 'alice@mail.test', pass: 'saved-pass' }, {});
  await w.send({ action: 'LOCK_VAULT' }, {});
  for (const host of ['social.test', 'login.social.test', 'second.test']) {
    const sender = { ...w.sender, url: `https://${host}/login` };
    for (const user of ['alice', 'ALICE@mail.test']) {
      assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user, pass: 'saved-pass' }, sender)).pending, false);
    }
    assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'Alice', email: 'alice@mail.test', pass: 'saved-pass' }, sender)).pending, false);
    assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'new-user', pass: 'saved-pass' }, sender)).pending, true);
  }
});

test('locked credential index respects sibling subdomains and shared hosting boundaries', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  for (const host of ['a.example.test', 'alice.github.io']) {
    await w.send({ action: 'SAVE_CREDENTIAL', siteName: host, siteUrl: `https://${host}`, user: 'alice', pass: 'saved-pass' }, {});
  }
  await w.send({ action: 'LOCK_VAULT' }, {});
  for (const [host, pending] of [['a.example.test', false], ['example.test', false], ['b.example.test', true],
    ['alice.github.io', false], ['github.io', true], ['bob.github.io', true]]) {
    assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'saved-pass' },
      { ...w.sender, url: `https://${host}/login` })).pending, pending, host);
  }
});

test('new identity adds an account and blank identity never overwrites a saved account', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  for (const [user, pass] of [['alice', 'a-pass'], ['bob', 'b-pass'], ['', 'unknown-pass']]) {
    const state = await w.send({ action: 'STAGE_CREDENTIAL', user, pass, hasIdentityFields: true });
    assert.equal(state.update, false);
    assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).success, true);
  }
  assert.deepEqual(JSON.parse(w.local.encVault).sites[0].accounts.map(a => [a.user, a.pass]),
    [['alice', 'a-pass'], ['bob', 'b-pass'], ['', 'unknown-pass']]);
  assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'ALICE', pass: 'changed-pass' })).update, true);
});

test('matching uses every saved account and updates the site that owns the identity', async () => {
  const vault = { sites: [
    { id: 'first', name: 'Example', url: 'https://example.test', accounts: [{ id: 'a', user: 'alice', pass: 'old' }] },
    { id: 'second', name: 'Example login', url: 'https://example.test/login', accounts: [{ id: 'b', user: 'alice', pass: 'current' }] }
  ] };
  const w = worker({ local: { encVault: JSON.stringify(vault) } });
  await w.send({ action: 'UNLOCK_VAULT', password: 'master-test' }, {});
  assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'current' })).pending, false);
  vault.sites[0].accounts[0].user = 'bob';
  const another = worker({ local: { encVault: JSON.stringify(vault) } });
  await another.send({ action: 'UNLOCK_VAULT', password: 'master-test' }, {});
  await another.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'new-pass' });
  await another.send({ action: 'CONFIRM_PENDING_CREDENTIAL' });
  const sites = JSON.parse(another.local.encVault).sites;
  assert.equal(sites[0].accounts.length, 1);
  assert.equal(sites[0].accounts[0].pass, 'old');
  assert.equal(sites[1].accounts[0].pass, 'new-pass');
});

test('same display name on an unrelated host creates a separate site', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  for (const host of ['example.test', 'other.test']) {
    await w.send({ action: 'SAVE_CREDENTIAL', siteName: 'Example', siteUrl: `https://${host}`, user: 'alice', pass: host }, {});
  }
  assert.equal(JSON.parse(w.local.encVault).sites.length, 2);
});

test('pending consent survives lock/unlock and retains a usable save button', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'pending-pass' });
  assert.equal(w.session['capture:1:0'].expires, null);
  await w.send({ action: 'LOCK_VAULT' }, {});
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).error, 'vault_locked');
  await w.send({ action: 'UNLOCK_VAULT', password: 'master-test' }, {});
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).success, true);
  assert.equal(JSON.parse(w.local.encVault).sites[0].accounts[0].pass, 'pending-pass');
});

test('storage failure leaves the persisted vault and active session intact', async () => {
  let failWrite = false;
  const local = new Proxy({}, { set(target, key, value) {
    if (failWrite && key === 'encVault') throw new Error('test storage failure');
    target[key] = value;
    return true;
  } });
  const w = worker({ local });
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  const before = local.encVault;
  await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'pending-pass' });
  failWrite = true;
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).success, false);
  assert.equal(local.encVault, before);
  assert.equal((await w.send({ action: 'GET_ALL_SITES' }, {})).sites.length, 0);
  failWrite = false;
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).success, true);
});

test('pending credentials survive same-origin navigation and reject other origins/frames', async () => {
  const w = worker();
  await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'test-password' });
  assert.equal((await w.send({ action: 'GET_PENDING_CREDENTIAL' }, { ...w.sender, url: 'https://example.test/home' })).pending, true);
  assert.equal((await w.send({ action: 'GET_PENDING_CREDENTIAL' }, { ...w.sender, url: 'https://other.test/' })).pending, false);
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' }, { ...w.sender, url: 'https://other.test/' })).success, false);
  assert.equal((await w.send({ action: 'GET_PENDING_CREDENTIAL' }, { ...w.sender, frameId: 3 })).pending, false);
  assert.equal((await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' })).error, 'vault_locked');
});

test('username-first flow, expiry and dismissal', async () => {
  const w = worker();
  await w.send({ action: 'STAGE_USERNAME', user: 'alice' });
  assert.equal((await w.send({ action: 'GET_PENDING_CREDENTIAL' })).pending, false);
  assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: '', pass: 'test-password' })).user, 'alice');
  w.session['capture:1:0'].expires = 0;
  assert.equal((await w.send({ action: 'GET_PENDING_CREDENTIAL' })).pending, false);
  await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'test-password' });
  await w.send({ action: 'DISMISS_CREDENTIAL' });
  assert.equal((await w.send({ action: 'GET_PENDING_CREDENTIAL' })).pending, false);
});

test('username/email first step retains both identifiers for a later password-only step', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  await w.send({ action: 'STAGE_USERNAME', user: 'alice', email: 'alice@mail.test' });
  await w.send({ action: 'STAGE_CREDENTIAL', user: '', email: '', pass: 'test-password', hasIdentityFields: false });
  await w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' });
  const account = JSON.parse(w.local.encVault).sites[0].accounts[0];
  assert.equal(account.user, 'alice');
  assert.equal(account.email, 'alice@mail.test');
});

test('cleared visible username cannot inherit a previously captured account identity', async () => {
  const w = worker();
  await w.send({ action: 'STAGE_USERNAME', user: 'alice' });
  assert.equal((await w.send({ action: 'STAGE_CREDENTIAL', user: '', pass: 'test-password', hasIdentityFields: true })).user, '');
});

function page(html, background = null) {
  const dom = new JSDOM(html, { url: 'https://example.test/signup', runScripts: 'outside-only', pretendToBeVisual: true });
  const win = dom.window, messages = [], roots = [];
  let receive;
  win.document.elementFromPoint = () => null;
  win.HTMLElement.prototype.getBoundingClientRect = () => ({ width: 300, height: 40, left: 0, right: 300, top: 0, bottom: 40 });
  const attach = win.Element.prototype.attachShadow;
  win.Element.prototype.attachShadow = function(options) { const root = attach.call(this, options); roots.push(root); return root; };
  win.chrome = { runtime: { lastError: null, onMessage: { addListener(fn) { receive = fn; } }, sendMessage(msg, cb) {
    messages.push(msg);
    if (background) { background.send(msg).then(result => cb?.(result)); return; }
    cb?.(msg.action === 'STAGE_CREDENTIAL' ? { pending: true, user: msg.user, domain: 'example.test' } : { pending: false });
  } } };
  win.eval(source('content.js'));
  return { dom, win, messages, roots, receive: msg => receive(msg, {}, () => {}) };
}

async function eventually(check) {
  for (let attempt = 0; attempt < 500; attempt++) {
    if (check()) return;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.fail('Timed out waiting for the expected state');
}

for (const kind of ['username', 'email', 'username-and-email']) {
  test(`Save click encrypts and restores URL, password and ${kind} after worker/browser restart`, async () => {
    const w = worker({ realCrypto: true });
    await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
    const identity = kind === 'email' ? '<input type="email" value="alice@mail.test">' :
      `<input autocomplete="username" value="alice">${kind === 'username-and-email' ? '<input type="email" value="alice@mail.test">' : ''}`;
    const p = page(`<form>${identity}<input type="password" value="test-password"></form>`, w);
    try {
      p.win.document.querySelector('[type=password]').dispatchEvent(new p.win.Event('change', { bubbles: true }));
      await eventually(() => p.win.document.querySelector('.reword-save-host'));
      const prompt = p.roots.at(-1);
      prompt.querySelector('button').click();
      await eventually(() => !p.win.document.querySelector('.reword-save-host'));
      assert.equal(w.local.encVault.includes('test-password'), false);
      const restored = worker({ local: w.local, realCrypto: true });
      assert.equal((await restored.send({ action: 'UNLOCK_VAULT', password: 'master-test' }, {})).success, true);
      const sites = (await restored.send({ action: 'GET_ALL_SITES' }, {})).sites;
      assert.equal(sites.length, 1);
      assert.equal(sites[0].url, 'https://example.test');
      assert.equal(sites[0].accounts[0].user, kind === 'email' ? 'alice@mail.test' : 'alice');
      assert.equal(sites[0].accounts[0].pass, 'test-password');
      assert.equal(sites[0].accounts[0].email || '', kind === 'username-and-email' ? 'alice@mail.test' : '');
      const user = kind === 'email' ? 'alice@mail.test' : 'alice';
      const email = kind === 'username-and-email' ? 'alice@mail.test' : '';
      assert.equal((await restored.send({ action: 'STAGE_CREDENTIAL', user, email, pass: 'test-password' })).pending, false);
      await restored.send({ action: 'LOCK_VAULT' }, {});
      assert.equal((await restored.send({ action: 'STAGE_CREDENTIAL', user, email, pass: 'test-password' })).pending, false);
    } finally { p.dom.window.close(); }
  });
}

test('one-click fill restores separate username and email and suppresses a duplicate prompt', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  await w.send({ action: 'SAVE_CREDENTIAL', user: 'alice', email: 'alice@mail.test', pass: 'saved-pass' });
  const p = page('<form><input autocomplete="username"><input type="email"><input type="password"></form>', w);
  try {
    p.receive({ action: 'TRIGGER_AUTOFILL', user: 'alice', email: 'alice@mail.test', pass: 'saved-pass' });
    assert.deepEqual(Array.from(p.win.document.querySelectorAll('form input'), field => field.value), ['alice', 'alice@mail.test', 'saved-pass']);
    await new Promise(resolve => setTimeout(resolve, 800));
    assert.equal(p.win.document.querySelector('.reword-save-host'), null);
  } finally { p.dom.window.close(); }
});

test('signup shows prompt after typing; captures email rather than display name', async () => {
  const p = page('<form><input name="displayName"><input type="email"><input type="password" autocomplete="new-password"><input type="password" autocomplete="new-password"></form>');
  try {
    const fields = p.win.document.querySelectorAll('input');
    fields[0].value = 'Display Name'; fields[1].value = 'alice@test'; fields[2].value = 'test-password';
    fields[2].dispatchEvent(new p.win.Event('input', { bubbles: true }));
    await new Promise(resolve => setTimeout(resolve, 800));
    const capture = p.messages.find(m => m.action === 'STAGE_CREDENTIAL');
    assert.equal(capture.user, 'alice@test');
    assert.equal(capture.pass, 'test-password');
    assert.ok(p.win.document.querySelector('.reword-save-host'));
    assert.equal(p.roots.at(-1).querySelector('p').textContent.includes('test-password'), false);
    p.roots.at(-1).querySelectorAll('button')[1].click();
    assert.ok(p.messages.some(m => m.action === 'DISMISS_CREDENTIAL'));
  } finally { p.dom.window.close(); }
});

test('username-only step and late mounted fields get badges', async () => {
  const p = page('<input autocomplete="username">');
  try {
    assert.equal(p.win.document.querySelectorAll('.reword-autofill-btn').length, 1);
    p.win.document.body.innerHTML = '<form><input type="email"><input type="password"></form>';
    await new Promise(resolve => setTimeout(resolve, 500));
    assert.equal(p.win.document.querySelectorAll('.reword-autofill-btn').length, 2);
  } finally { p.dom.window.close(); }
});

test('open shadow roots expose credential fields without attaching to search', () => {
  const p = page('<div id="host"></div><input type="search">');
  try {
    const root = p.win.document.getElementById('host').attachShadow({ mode: 'open' });
    root.innerHTML = '<form><input type="email"><input type="password"></form>';
    root.querySelector('input').dispatchEvent(new p.win.FocusEvent('focusin', { bubbles: true, composed: true }));
    assert.equal(p.win.document.querySelectorAll('.reword-autofill-btn').length, 2);
  } finally { p.dom.window.close(); }
});

test('one-click fill leaves new-password and OTP fields untouched', () => {
  const p = page('<form><input type="email"><input type="password" autocomplete="new-password"><input type="password" autocomplete="one-time-code"></form>');
  try {
    p.receive({ action: 'TRIGGER_AUTOFILL', user: 'alice@test', pass: 'saved-test-password' });
    const fields = p.win.document.querySelectorAll('form input');
    assert.equal(fields[0].value, 'alice@test');
    assert.equal(fields[1].value, '');
    assert.equal(fields[2].value, '');
    assert.equal(p.win.document.querySelectorAll('.reword-autofill-btn').length, 2);
  } finally { p.dom.window.close(); }
});

test('parallel confirmed saves do not overwrite another account', async () => {
  const w = worker();
  await w.send({ action: 'SETUP_NEW_VAULT', password: 'master-test' }, {});
  const another = { ...w.sender, tab: { id: 2 } };
  await w.send({ action: 'STAGE_CREDENTIAL', user: 'alice', pass: 'test-a' });
  await w.send({ action: 'STAGE_CREDENTIAL', user: 'bob', pass: 'test-b' }, another);
  await Promise.all([
    w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' }),
    w.send({ action: 'CONFIRM_PENDING_CREDENTIAL' }, another)
  ]);
  assert.deepEqual(JSON.parse(w.local.encVault).sites[0].accounts.map(a => a.user).sort(), ['alice', 'bob']);
});

test('shield follows page and container scrolling immediately, hides when clipped, then returns', () => {
  const p = page('<div id="panel" style="overflow:auto"><input type="email"></div>');
  try {
    const field = p.win.document.querySelector('input');
    const panel = p.win.document.getElementById('panel');
    const badge = p.win.document.querySelector('.reword-autofill-btn');
    let top = 120;
    field.getBoundingClientRect = () => ({ width: 300, height: 40, left: 20, right: 320, top, bottom: top + 40 });
    panel.getBoundingClientRect = () => ({ width: 400, height: 300, left: 0, right: 400, top: 50, bottom: 350 });
    p.win.dispatchEvent(new p.win.Event('scroll'));
    assert.equal(badge.style.top, '128px');
    assert.equal(badge.style.left, '290px');
    top = 80;
    panel.dispatchEvent(new p.win.Event('scroll'));
    assert.equal(badge.style.top, '88px');
    top = 20;
    panel.dispatchEvent(new p.win.Event('scroll'));
    assert.equal(badge.style.display, 'none');
    top = 100;
    panel.dispatchEvent(new p.win.Event('scroll'));
    assert.equal(badge.style.display, 'flex');
    assert.equal(badge.style.top, '108px');
    top = -40;
    p.win.dispatchEvent(new p.win.Event('scroll'));
    assert.equal(badge.style.display, 'none');
    // Geometry must never be animated: a transition on top/left causes scroll drift.
    const rule = source('content.css').match(/\.reword-autofill-btn\s*\{([^}]+)\}/)[1];
    const transition = rule.match(/transition:\s*([^;]+)/)[1];
    assert.doesNotMatch(transition, /\b(all|top|left|width|height)\b/);
  } finally { p.dom.window.close(); }
});

function popup(sites = []) {
  const dom = new JSDOM(source('popup.html'), { url: 'https://extension.test/popup.html', runScripts: 'outside-only', pretendToBeVisual: true });
  const win = dom.window;
  win.chrome = {
    runtime: { lastError: null, getManifest: () => ({ version: '1.3.3' }), sendMessage(msg, cb) {
      cb?.(msg.action === 'GET_ALL_SITES' ? { sites } : { isUnlocked: true, matches: [] });
    } },
    storage: { local: { async get() { return {}; } } },
    tabs: { async query() { return [{ id: 1, url: 'https://example.test/login' }]; } }
  };
  win.eval(source('popup.js').replace(/^import .*;$/m, '') + '\n;window.renderMatches = renderCurrentSiteMatches; window.renderSites = renderAllSites;');
  return { dom, win };
}

test('popup quick add opens the modal and focuses the existing username field', () => {
  const p = popup();
  try {
    p.win.renderMatches([]);
    p.win.document.getElementById('btn-quick-add-cur').click();
    assert.equal(p.win.document.getElementById('modal-add').classList.contains('active'), true);
    assert.equal(p.win.document.activeElement.id, 'add-user');
  } finally { p.dom.window.close(); }
});

test('popup favicon fallback works without inline handlers and treats vault data as text', () => {
  const hostile = '\"><img src=x onerror="alert(1)">';
  const p = popup([{ name: hostile, url: 'https://example.test', accounts: [{ user: hostile, pass: 'test' }] }]);
  try {
    p.win.renderMatches([{ siteName: hostile, siteUrl: 'https://example.test', user: hostile }]);
    p.win.renderSites();
    const doc = p.win.document;
    assert.equal(doc.querySelector('.match-user').textContent, hostile);
    assert.equal(doc.querySelector('.site-title').textContent, hostile);
    for (const element of doc.querySelectorAll('*')) {
      assert.equal(Array.from(element.attributes).some(attr => /^on/i.test(attr.name)), false);
    }
    const images = doc.querySelectorAll('.site-fav-img');
    assert.equal(images.length, 2);
    for (const image of images) {
      image.dispatchEvent(new p.win.Event('error'));
      assert.equal(image.style.display, 'none');
      assert.equal(image.nextElementSibling.style.display, 'flex');
    }
  } finally { p.dom.window.close(); }
});
