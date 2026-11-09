// ReWord Chrome Extension - Popup Logic
import { extractDomain } from './crypto.js';

let CURRENT_TAB = null;
let CURRENT_DOMAIN = '';
let CACHED_VAULT = null;

// DOM Elements
const screenLoading = document.getElementById('screen-loading');
const screenLogin = document.getElementById('screen-login');
const screenConnect = document.getElementById('screen-connect');
const screenMain = document.getElementById('screen-main');

const formLogin = document.getElementById('form-login');
const loginPass = document.getElementById('login-pass');
const loginHint = document.getElementById('login-hint');
const btnToggleLoginPass = document.getElementById('btn-toggle-login-pass');

const formConnect = document.getElementById('form-connect');
const connSid = document.getElementById('conn-sid');
const connWurl = document.getElementById('conn-wurl');
const connPass = document.getElementById('conn-pass');

const linkGotoConnect = document.getElementById('link-goto-connect');
const linkGotoLogin = document.getElementById('link-goto-login');

const currentDomainLabel = document.getElementById('current-domain-label');
const currentSiteMatches = document.getElementById('current-site-matches');
const vaultSearchInput = document.getElementById('vault-search-input');
const vaultList = document.getElementById('vault-list');

const btnSyncNow = document.getElementById('btn-sync-now');
const btnLock = document.getElementById('btn-lock');
const btnOpenGenerator = document.getElementById('btn-open-generator');
const btnAddCredential = document.getElementById('btn-add-credential');

const modalAdd = document.getElementById('modal-add');
const btnCloseModal = document.getElementById('btn-close-modal');
const formSaveCredential = document.getElementById('form-save-credential');
const addSiteName = document.getElementById('add-site-name');
const addSiteUrl = document.getElementById('add-site-url');
const addUser = document.getElementById('add-user');
const addPass = document.getElementById('add-pass');
const addNotes = document.getElementById('add-notes');
const modalTitle = document.getElementById('modal-title');
const btnQuickAddCur = document.getElementById('btn-quick-add-cur');
// Set while the add/edit modal edits an existing account: { siteId, accountId }
let EDITING = null;
const btnGenForInput = document.getElementById('btn-gen-for-input');
const btnToggleAddPass = document.getElementById('btn-toggle-add-pass');
const vaultStats = document.getElementById('vault-stats');
const tagFilter = document.getElementById('tag-filter');
const tagPicker = document.getElementById('tag-picker');
const tagNewInput = document.getElementById('tag-new-input');
const btnTagAdd = document.getElementById('btn-tag-add');

const modalGenerator = document.getElementById('modal-generator');
const btnCloseGenModal = document.getElementById('btn-close-gen-modal');
const genPasswordDisplay = document.getElementById('gen-password-display');
const btnCopyGenerated = document.getElementById('btn-copy-generated');
const genLenSlider = document.getElementById('gen-len-slider');
const genLenLabel = document.getElementById('gen-len-label');
const btnRegenerate = document.getElementById('btn-regenerate');

const toastEl = document.getElementById('toast');
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

// Normalize a stored site URL for opening in a tab. Returns '' for empty/androidapp:// URLs.
function normalizeSiteUrl(raw) {
  const first = String(raw ?? '').split(/[,;\s]+/).map(part => part.trim()).find(Boolean) || '';
  if (!first || /^androidapp:/i.test(first)) return '';
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(first) ? first : 'https://' + first;
  try {
    const url = new URL(withScheme);
    return /^https?:$/.test(url.protocol) ? url.href : '';
  } catch {
    return '';
  }
}

function openSite(raw) {
  const url = normalizeSiteUrl(raw);
  if (!url) return;
  chrome.tabs.create({ url });
}

const EYE_ICON = '<svg class="eye-icon" viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>';
const EYE_OFF_ICON = '<svg class="eye-icon" viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z"/></svg>';

// Keeps the eye button's icon/state in sync with the input type (shown = eye-off icon).
function setPasswordVisible(input, button, visible) {
  input.type = visible ? 'text' : 'password';
  button.innerHTML = visible ? EYE_OFF_ICON : EYE_ICON;
  button.setAttribute('aria-pressed', String(visible));
  button.title = visible ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور';
}

// ---- Tags (site categories, comma-separated in site.tags — same format as web/Android) ----
let ALL_SITES = [];
let ACTIVE_TAG = null;
const SELECTED_TAGS = new Set();
const splitTags = (tags) => String(tags || '').split(',').map(t => t.trim()).filter(Boolean);
function allTags() {
  const map = new Map();
  ALL_SITES.forEach(site => splitTags(site.tags).forEach(t => { if (!map.has(t.toLowerCase())) map.set(t.toLowerCase(), t); }));
  SELECTED_TAGS.forEach(t => { if (!map.has(t.toLowerCase())) map.set(t.toLowerCase(), t); });
  return [...map.values()].sort((a, b) => a.localeCompare(b, 'ar'));
}

const OPEN_ICON = '<svg viewBox="0 0 24 24" width="12" height="12" fill="currentColor"><path d="M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7zM5 5h6V3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-6h-2v6H5V5z"/></svg>';

// Image errors don't bubble; capture them without inline handlers (Manifest V3 CSP).
document.addEventListener('error', event => {
  const image = event.target;
  if (!image.matches?.('img.site-fav-img')) return;
  image.style.display = 'none';
  const fallback = image.nextElementSibling;
  if (fallback?.classList.contains('site-fav-fallback')) fallback.style.display = 'flex';
}, true);

// Utility: Show Screen
function showScreen(screen) {
  [screenLoading, screenLogin, screenConnect, screenMain].forEach(s => s.classList.remove('active'));
  screen.classList.add('active');
}

// Utility: Toast
function showToast(msg, duration = 2000) {
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  setTimeout(() => toastEl.classList.remove('show'), duration);
}

// Utility: Copy to Clipboard
async function copyText(text, label = 'تم النسخ') {
  try {
    await navigator.clipboard.writeText(text);
    showToast(`✓ ${label}`);
  } catch {
    showToast('فشل النسخ إلى الحافظة');
  }
}

// Password Generator Function
function generatePassword(len = 16) {
  const chars = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%^&*()-_=+';
  let pwd = '';
  const cryptoObj = window.crypto || window.msCrypto;
  const values = new Uint32Array(len);
  cryptoObj.getRandomValues(values);
  for (let i = 0; i < len; i++) {
    pwd += chars[values[i] % chars.length];
  }
  return pwd;
}

// Initialize Popup
// Version label + one-time "updated" notice (set by background.js on chrome.runtime.onInstalled)
async function showVersionInfo() {
  const version = chrome.runtime.getManifest().version;
  const versionEl = document.getElementById('ext-version');
  if (versionEl) versionEl.textContent = `ReWord v${version}`;
  const { updateNotice } = await chrome.storage.local.get('updateNotice');
  if (!updateNotice) return;
  const banner = document.getElementById('update-banner');
  document.getElementById('update-banner-text').textContent =
    `تم تحديث الإضافة إلى الإصدار ${updateNotice.to}` +
    (updateNotice.from ? ` (من ${updateNotice.from})` : '') +
    ' ✓ — أعد تحميل الصفحات المفتوحة لتعمل التحديثات عليها.';
  banner.hidden = false;
  document.getElementById('btn-dismiss-update').addEventListener('click', async () => {
    banner.hidden = true;
    await chrome.storage.local.remove('updateNotice');
  });
  // Clear the toolbar badge now that the user has seen the notice
  chrome.action.setBadgeText({ text: '' });
}

async function init() {
  showVersionInfo().catch(() => {});
  // Get active tab info
  try {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs && tabs[0]) {
      CURRENT_TAB = tabs[0];
      CURRENT_DOMAIN = extractDomain(CURRENT_TAB.url || '');
      currentDomainLabel.textContent = CURRENT_DOMAIN || 'صفحة داخلية';
    }
  } catch (e) {
    console.warn('Could not query active tab:', e);
  }

  // Get Vault Status from background service worker
  chrome.runtime.sendMessage({ action: 'GET_STATUS' }, (status) => {
    if (chrome.runtime.lastError || !status) {
      showScreen(screenConnect);
      return;
    }

    if (status.isUnlocked) {
      loadMainVault();
    } else if (status.hasVault) {
      if (status.hint) {
        loginHint.textContent = `تلميح: ${status.hint}`;
      }
      showScreen(screenLogin);
      loginPass.focus();
    } else {
      if (status.syncId) connSid.value = status.syncId;
      if (status.workerUrl) connWurl.value = status.workerUrl;
      showScreen(screenConnect);
    }
  });
}

// Load Unlocked Main Vault
function loadMainVault() {
  showScreen(screenMain);

  // Get matching credentials for current tab
  chrome.runtime.sendMessage({
    action: 'GET_MATCHING_CREDENTIALS',
    url: CURRENT_TAB?.url || ''
  }, (res) => {
    renderCurrentSiteMatches(res?.matches || []);
  });

  // Get all accounts for search list
  chrome.runtime.sendMessage({ action: 'GET_STATUS' }, () => {
    // We can fetch entire vault by calling GET_MATCHING_CREDENTIALS with empty or broad match
    chrome.runtime.sendMessage({ action: 'GET_MATCHING_CREDENTIALS', url: '' }, (res) => {
      // In background, let's load all sites
    });
  });

  renderAllSites();
}

// Render Matching Accounts for Current Active Tab
function renderCurrentSiteMatches(matches) {
  currentSiteMatches.innerHTML = '';
  currentSiteMatches.hidden = !matches || matches.length === 0;
  if (!matches || matches.length === 0) return;

  matches.forEach((acc) => {
    const card = document.createElement('div');
    card.className = 'match-card';
    const firstUrl = acc.siteUrl || (CURRENT_DOMAIN && CURRENT_DOMAIN !== '-' ? 'https://' + CURRENT_DOMAIN : '');
    const fav = firstUrl ? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(firstUrl)}&sz=64` : '';
    const initial = (acc.siteName || acc.user || CURRENT_DOMAIN || 'W').charAt(0).toUpperCase();

    card.innerHTML = `
      <div class="match-left">
        <div class="site-fav-box">
          ${fav ? `<img src="${fav}" class="site-fav-img" alt="">` : ''}
          <span class="site-fav-fallback" style="${fav ? 'display:none;' : 'display:flex;'}">${escapeHtml(initial)}</span>
        </div>
        <div class="match-user-meta">
          <div class="match-user" title="${escapeHtml(acc.user)}">${escapeHtml(acc.user)}</div>
          <div class="match-sub">${escapeHtml(acc.siteName || CURRENT_DOMAIN)}</div>
        </div>
      </div>
      <div class="match-actions">
        <button class="btn-fill" title="ملء تلقائي للصفحة">تعبئة ⚡</button>
        <button class="mini-btn btn-copy-user" title="نسخ اسم المستخدم">👤</button>
        <button class="mini-btn btn-copy-pass" title="نسخ كلمة المرور">🔑</button>
      </div>
    `;

    // Fill button
    card.querySelector('.btn-fill').addEventListener('click', () => {
      triggerAutofill(acc.user, acc.pass);
    });

    // Copy user
    card.querySelector('.btn-copy-user').addEventListener('click', () => {
      copyText(acc.user, 'تم نسخ اسم المستخدم');
    });

    // Copy pass
    card.querySelector('.btn-copy-pass').addEventListener('click', () => {
      copyText(acc.pass, 'تم نسخ كلمة المرور');
    });

    currentSiteMatches.appendChild(card);
  });
}

// Open the add/edit modal. Passing `edit` ({ siteId, accountId }) switches it to edit mode.
function renderTagPicker() {
  tagPicker.innerHTML = '';
  allTags().forEach(tag => {
    const chip = document.createElement('button');
    chip.type = 'button';
    const on = [...SELECTED_TAGS].some(t => t.toLowerCase() === tag.toLowerCase());
    chip.className = 'tag-chip' + (on ? ' active' : '');
    chip.textContent = tag;
    chip.addEventListener('click', () => {
      const existing = [...SELECTED_TAGS].find(t => t.toLowerCase() === tag.toLowerCase());
      if (existing) SELECTED_TAGS.delete(existing); else SELECTED_TAGS.add(tag);
      renderTagPicker();
    });
    tagPicker.appendChild(chip);
  });
}

function addNewTag() {
  const tag = tagNewInput.value.replace(/,/g, ' ').trim();
  if (!tag) return;
  const existing = allTags().find(t => t.toLowerCase() === tag.toLowerCase());
  SELECTED_TAGS.add(existing || tag);
  tagNewInput.value = '';
  renderTagPicker();
}

function openAccountModal({ siteName = '', siteUrl = '', user = '', pass = '', notes = '', tags = '', edit = null } = {}) {
  EDITING = edit;
  modalTitle.textContent = edit ? 'تعديل الحساب' : 'إضافة حساب جديد';
  addSiteName.value = siteName;
  addSiteUrl.value = siteUrl;
  addUser.value = user;
  addPass.value = pass;
  setPasswordVisible(addPass, btnToggleAddPass, false);
  addNotes.value = notes;
  SELECTED_TAGS.clear();
  splitTags(tags).forEach(t => SELECTED_TAGS.add(t));
  tagNewInput.value = '';
  renderTagPicker();
  modalAdd.classList.add('active');
  addUser.focus();
}

btnQuickAddCur.addEventListener('click', () => {
  openAccountModal({
    siteName: CURRENT_DOMAIN && CURRENT_DOMAIN !== '-' ? CURRENT_DOMAIN : '',
    siteUrl: CURRENT_TAB?.url || ''
  });
});

// Trigger Autofill via Content Script in the Active Tab
function triggerAutofill(user, pass) {
  if (!CURRENT_TAB?.id) return;
  chrome.tabs.sendMessage(CURRENT_TAB.id, {
    action: 'TRIGGER_AUTOFILL',
    user,
    pass
  }, { frameId: 0 }, () => {
    showToast('تمت تعبئة البيانات في الصفحة ✓');
    setTimeout(() => window.close(), 600);
  });
}

// Render All Sites in Vault Search List
function renderAllSites() {
  vaultList.innerHTML = '<div style="text-align:center;color:#94a3b8;font-size:11px;padding:8px;">جاري تحميل الحسابات...</div>';

  chrome.runtime.sendMessage({ action: 'GET_ALL_SITES' }, (res) => {
    vaultList.innerHTML = '';
    const sites = res?.sites || [];
    ALL_SITES = sites;
    const accountsTotal = sites.reduce((n, s) => n + (s.accounts?.length || 0), 0);
    vaultStats.textContent = `${sites.length} موقع • ${accountsTotal} حساب`;
    if (ACTIVE_TAG && !allTags().some(t => t.toLowerCase() === ACTIVE_TAG.toLowerCase())) ACTIVE_TAG = null;
    renderTagFilter();

    if (sites.length === 0) {
      vaultList.innerHTML = '<div style="text-align:center;color:#94a3b8;font-size:11px;padding:12px;">لا توجد حسابات مسجلة بعد</div>';
      return;
    }

    sites.forEach((site) => {
      const siteItem = document.createElement('div');
      siteItem.className = 'vault-site-item';
      siteItem.dataset.tags = splitTags(site.tags).map(t => t.toLowerCase()).join('|');

      const firstUrl = site.url ? site.url.split(/[,;\s]+/)[0].trim() : '';
      const fav = firstUrl ? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(firstUrl)}&sz=64` : '';
      const initial = (site.name || 'W').charAt(0).toUpperCase();
      const accountsCount = site.accounts?.length || 0;
      const canOpen = !!normalizeSiteUrl(site.url);

      siteItem.innerHTML = `
        <div class="site-name-row">
          <div class="site-row-left">
            <div class="site-fav-box">
              ${fav ? `<img src="${fav}" class="site-fav-img" alt="">` : ''}
              <span class="site-fav-fallback" style="${fav ? 'display:none;' : 'display:flex;'}">${escapeHtml(initial)}</span>
            </div>
            <div class="site-meta">
              <span class="site-title">${escapeHtml(site.name || 'موقع بدون اسم')}</span>
              ${firstUrl ? `<span class="site-url-sub">${escapeHtml(firstUrl.replace(/^https?:\/\//, '').replace(/\/.*$/, ''))}</span>` : ''}
              ${splitTags(site.tags).length ? `<span class="site-tags">${splitTags(site.tags).slice(0, 3).map(t => `<span class="site-tag">${escapeHtml(t)}</span>`).join('')}</span>` : ''}
            </div>
          </div>
          <div class="site-row-right">
            ${canOpen ? `<button type="button" class="mini-btn open-site-btn" title="فتح الموقع">${OPEN_ICON}</button>` : ''}
            <span class="site-acc-count">${accountsCount} ▾</span>
          </div>
        </div>
        <div class="site-accounts-container" hidden>
          ${(site.accounts || []).map((acc, aIdx) => `
            <div class="acc-entry">
              <div class="acc-main">
                <div class="match-user" title="${escapeHtml(acc.user)}">${escapeHtml(acc.user)}</div>
                <div class="match-actions">
                  ${canOpen ? `<button type="button" class="mini-btn open-site-btn" title="فتح الموقع">${OPEN_ICON}</button>` : ''}
                  <button type="button" class="mini-btn edit-site-acc" data-acc="${aIdx}" title="تعديل الحساب">✎</button>
                  <button type="button" class="mini-btn copy-site-user" data-acc="${aIdx}" title="نسخ المستخدم">👤</button>
                  <button type="button" class="mini-btn copy-site-pass" data-acc="${aIdx}" title="نسخ كلمة المرور">🔑</button>
                </div>
              </div>
              ${acc.notes ? `<div class="acc-notes">${escapeHtml(acc.notes)}</div>` : ''}
            </div>
          `).join('')}
        </div>
      `;

      // Toggle accordion on row click
      siteItem.querySelector('.site-name-row').addEventListener('click', () => {
        const accContainer = siteItem.querySelector('.site-accounts-container');
        accContainer.hidden = !accContainer.hidden;
      });

      // Open site in a new tab (must not toggle the row)
      siteItem.querySelectorAll('.open-site-btn').forEach((btn) => {
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          openSite(site.url);
        });
      });

      // Edit account
      siteItem.querySelectorAll('.edit-site-acc').forEach((btn) => {
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          const acc = site.accounts[parseInt(btn.dataset.acc, 10)];
          if (!acc) return;
          openAccountModal({
            siteName: site.name || '',
            siteUrl: site.url || '',
            user: acc.user || '',
            pass: acc.pass || '',
            notes: acc.notes || '',
            tags: site.tags || '',
            edit: { siteId: site.id, accountId: acc.id }
          });
        });
      });

      // Bind copy buttons
      siteItem.querySelectorAll('.copy-site-user').forEach((btn) => {
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          const aIdx = parseInt(btn.dataset.acc, 10);
          const acc = site.accounts[aIdx];
          if (acc) copyText(acc.user, 'تم نسخ اسم المستخدم');
        });
      });

      siteItem.querySelectorAll('.copy-site-pass').forEach((btn) => {
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          const aIdx = parseInt(btn.dataset.acc, 10);
          const acc = site.accounts[aIdx];
          if (acc) copyText(acc.pass, 'تم نسخ كلمة المرور');
        });
      });

      vaultList.appendChild(siteItem);
    });
    applyVaultFilters();
  });
}

function renderTagFilter() {
  const tags = allTags();
  tagFilter.hidden = tags.length === 0;
  tagFilter.innerHTML = '';
  const make = (label, value) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    const on = value === null ? ACTIVE_TAG === null : (ACTIVE_TAG || '').toLowerCase() === value.toLowerCase();
    chip.className = 'tag-chip' + (on ? ' active' : '');
    chip.textContent = label;
    chip.addEventListener('click', () => {
      ACTIVE_TAG = value === null || on ? null : value;
      renderTagFilter();
      applyVaultFilters();
    });
    tagFilter.appendChild(chip);
  };
  make('الكل', null);
  tags.forEach(t => make(t, t));
}

// Search text + selected category together
function applyVaultFilters() {
  const q = vaultSearchInput.value.toLowerCase().trim();
  const tag = ACTIVE_TAG ? ACTIVE_TAG.toLowerCase() : null;
  vaultList.querySelectorAll('.vault-site-item').forEach((item) => {
    const textOk = !q || item.textContent.toLowerCase().includes(q);
    const tagOk = !tag || (item.dataset.tags || '').split('|').includes(tag);
    item.style.display = textOk && tagOk ? 'block' : 'none';
  });
}

// Event Listeners: Login Form
formLogin.addEventListener('submit', (e) => {
  e.preventDefault();
  const password = loginPass.value;
  if (!password) return;

  const btn = document.getElementById('btn-do-login');
  btn.disabled = true;
  btn.textContent = 'جاري التحقق...';

  chrome.runtime.sendMessage({ action: 'UNLOCK_VAULT', password }, (res) => {
    btn.disabled = false;
    btn.textContent = 'فتح الخزينة';

    if (res?.success) {
      loginPass.value = '';
      loadMainVault();
      showToast('تم فتح الخزينة بنجاح ✓');
    } else {
      showToast('كلمة المرور غير صحيحة ✗');
      loginPass.value = '';
      loginPass.focus();
    }
  });
});

// Toggle password visibility in login
btnToggleLoginPass.addEventListener('click', () => {
  setPasswordVisible(loginPass, btnToggleLoginPass, loginPass.type === 'password');
});

btnToggleAddPass.addEventListener('click', () => {
  setPasswordVisible(addPass, btnToggleAddPass, addPass.type === 'password');
});

btnTagAdd.addEventListener('click', addNewTag);
tagNewInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); addNewTag(); }
});

// Event Listeners: Connect Form (From Cloudflare Worker)
formConnect.addEventListener('submit', (e) => {
  e.preventDefault();
  const sid = connSid.value.trim();
  const wurl = connWurl.value.trim();
  const pass = connPass.value;

  if (!sid || !wurl || !pass) {
    showToast('يرجى ملء جميع الحقول');
    return;
  }

  const btn = document.getElementById('btn-do-connect');
  btn.disabled = true;
  btn.textContent = 'جاري جلب الخزينة...';

  chrome.runtime.sendMessage({
    action: 'CONNECT_VAULT',
    syncId: sid,
    workerUrl: wurl,
    password: pass
  }, (res) => {
    btn.disabled = false;
    btn.textContent = 'جلب وفتح الخزينة';

    if (res?.success) {
      loadMainVault();
      showToast('تم ربط الخزينة بنجاح 🎉');
    } else {
      const errs = {
        vault_not_found: 'لم يتم العثور على الخزينة في السيرفر',
        invalid_password: 'كلمة المرور غير صحيحة للبيانات المستلمة',
        missing_fields: 'بيانات غير مكتملة'
      };
      showToast(errs[res?.error] || 'فشل الاتصال بالـ Worker');
    }
  });
});

// Switch screens
linkGotoConnect.addEventListener('click', (e) => {
  e.preventDefault();
  showScreen(screenConnect);
});

linkGotoLogin.addEventListener('click', (e) => {
  e.preventDefault();
  showScreen(screenLogin);
});

// Lock Vault
btnLock.addEventListener('click', () => {
  chrome.runtime.sendMessage({ action: 'LOCK_VAULT' }, () => {
    showScreen(screenLogin);
    showToast('تم قفل الخزينة 🔒');
  });
});

// Sync Now
btnSyncNow.addEventListener('click', () => {
  btnSyncNow.style.transform = 'rotate(180deg)';
  chrome.runtime.sendMessage({ action: 'SYNC_NOW' }, (res) => {
    setTimeout(() => { btnSyncNow.style.transform = ''; }, 400);
    if (res?.success) {
      showToast(res.updated ? 'تمت المزامنة وتحديث البيانات ✓' : 'البيانات محدثة بالفعل ✓');
      loadMainVault();
    } else {
      showToast('فشلت المزامنة، تأكد من الإنترنت');
    }
  });
});

// Add Credential Modal
btnAddCredential.addEventListener('click', () => {
  openAccountModal({
    siteName: CURRENT_DOMAIN ? CURRENT_DOMAIN.split('.')[0].toUpperCase() : '',
    siteUrl: CURRENT_TAB?.url || ''
  });
});

btnCloseModal.addEventListener('click', () => {
  modalAdd.classList.remove('active');
  EDITING = null;
});

btnGenForInput.addEventListener('click', () => {
  addPass.value = generatePassword(16);
  setPasswordVisible(addPass, btnToggleAddPass, true);
  showToast('تم توليد كلمة مرور قوية');
});

formSaveCredential.addEventListener('submit', (e) => {
  e.preventDefault();
  const siteName = addSiteName.value.trim();
  const siteUrl = addSiteUrl.value.trim();
  const user = addUser.value.trim();
  const pass = addPass.value;
  const notes = addNotes.value.trim();
  if (tagNewInput.value.trim()) addNewTag(); // typed a category but did not press "+"
  const tags = [...SELECTED_TAGS].join(', ');

  if (!siteName || !user || !pass) return;

  const payload = EDITING
    ? { action: 'UPDATE_ACCOUNT', siteId: EDITING.siteId, accountId: EDITING.accountId, siteName, siteUrl, user, pass, notes, tags }
    : { action: 'SAVE_CREDENTIAL', siteName, siteUrl, user, pass, notes, tags };

  chrome.runtime.sendMessage(payload, (res) => {
    if (res?.success) {
      modalAdd.classList.remove('active');
      EDITING = null;
      showToast('تم حفظ الحساب بنجاح ✓');
      loadMainVault();
    } else {
      showToast('حدث خطأ أثناء الحفظ');
    }
  });
});

// Password Generator Modal
btnOpenGenerator.addEventListener('click', () => {
  genPasswordDisplay.textContent = generatePassword(parseInt(genLenSlider.value, 10));
  modalGenerator.classList.add('active');
});

btnCloseGenModal.addEventListener('click', () => {
  modalGenerator.classList.remove('active');
});

genLenSlider.addEventListener('input', () => {
  genLenLabel.textContent = genLenSlider.value;
  genPasswordDisplay.textContent = generatePassword(parseInt(genLenSlider.value, 10));
});

btnRegenerate.addEventListener('click', () => {
  genPasswordDisplay.textContent = generatePassword(parseInt(genLenSlider.value, 10));
});

btnCopyGenerated.addEventListener('click', () => {
  copyText(genPasswordDisplay.textContent, 'تم نسخ كلمة المرور المولدة');
});

// Search Filter in Vault
vaultSearchInput.addEventListener('input', applyVaultFilters);

// Initialize on DOM ready
document.addEventListener('DOMContentLoaded', init);
