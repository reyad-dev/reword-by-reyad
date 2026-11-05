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
    '\n;this.siteMatchesDomain = siteMatchesDomain; this.sameUser = sameUser; this.hostMatches = hostMatches;', ctx);
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

function worker() {
  const local = {}, session = {};
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
    chrome, console, structuredClone, URL, Date, Math,
    CV: { enc: async value => JSON.stringify(value), dec: async value => JSON.parse(value) },
    cleanWorkerUrl: value => value || '', extractDomain: value => new URL(value).hostname,
    // Real matching helpers from crypto.js (the same code the extension ships)
    siteMatchesDomain: cryptoHelpers.siteMatchesDomain, sameUser: cryptoHelpers.sameUser
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

function page(html) {
  const dom = new JSDOM(html, { url: 'https://example.test/signup', runScripts: 'outside-only', pretendToBeVisual: true });
  const win = dom.window, messages = [], roots = [];
  let receive;
  win.document.elementFromPoint = () => null;
  win.HTMLElement.prototype.getBoundingClientRect = () => ({ width: 300, height: 40, left: 0, right: 300, top: 0, bottom: 40 });
  const attach = win.Element.prototype.attachShadow;
  win.Element.prototype.attachShadow = function(options) { const root = attach.call(this, options); roots.push(root); return root; };
  win.chrome = { runtime: { lastError: null, onMessage: { addListener(fn) { receive = fn; } }, sendMessage(msg, cb) {
    messages.push(msg);
    cb?.(msg.action === 'STAGE_CREDENTIAL' ? { pending: true, user: msg.user, domain: 'example.test' } : { pending: false });
  } } };
  win.eval(source('content.js'));
  return { dom, win, messages, roots, receive: msg => receive(msg, {}, () => {}) };
}

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
