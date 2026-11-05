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
const btnGenForInput = document.getElementById('btn-gen-for-input');

const modalGenerator = document.getElementById('modal-generator');
const btnCloseGenModal = document.getElementById('btn-close-gen-modal');
const genPasswordDisplay = document.getElementById('gen-password-display');
const btnCopyGenerated = document.getElementById('btn-copy-generated');
const genLenSlider = document.getElementById('gen-len-slider');
const genLenLabel = document.getElementById('gen-len-label');
const btnRegenerate = document.getElementById('btn-regenerate');

const toastEl = document.getElementById('toast');

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

  if (!matches || matches.length === 0) {
    currentSiteMatches.innerHTML = `
      <div class="no-match-box">
        <div style="font-size:11px;color:#94a3b8;padding:4px 0;">
          لا توجد حسابات مسجلة لـ <strong>${CURRENT_DOMAIN}</strong>
        </div>
        <button type="button" class="btn-quick-add" id="btn-quick-add-cur">+ إضافة حساب لـ ${CURRENT_DOMAIN}</button>
      </div>
    `;
    const qBtn = currentSiteMatches.querySelector('#btn-quick-add-cur');
    if (qBtn) {
      qBtn.addEventListener('click', () => {
        document.getElementById('add-site-name').value = CURRENT_DOMAIN && CURRENT_DOMAIN !== '-' ? CURRENT_DOMAIN : '';
        document.getElementById('add-site-url').value = CURRENT_TAB?.url || '';
        document.getElementById('modal-title').textContent = 'إضافة حساب جديد';
        modalAdd.classList.add('active');
        document.getElementById('add-site-user').focus();
      });
    }
    return;
  }

  matches.forEach((acc) => {
    const card = document.createElement('div');
    card.className = 'match-card';
    const firstUrl = acc.siteUrl || (CURRENT_DOMAIN && CURRENT_DOMAIN !== '-' ? 'https://' + CURRENT_DOMAIN : '');
    const fav = firstUrl ? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(firstUrl)}&sz=64` : '';
    const initial = (acc.siteName || acc.user || CURRENT_DOMAIN || 'W').charAt(0).toUpperCase();

    card.innerHTML = `
      <div class="match-left">
        <div class="site-fav-box">
          ${fav ? `<img src="${fav}" class="site-fav-img" onerror="this.style.display='none';this.nextElementSibling.style.display='flex';" alt="">` : ''}
          <span class="site-fav-fallback" style="${fav ? 'display:none;' : 'display:flex;'}">${initial}</span>
        </div>
        <div class="match-user-meta">
          <div class="match-user" title="${acc.user}">${acc.user}</div>
          <div class="match-sub">${acc.siteName || CURRENT_DOMAIN}</div>
        </div>
      </div>
      <div class="match-actions">
        <button class="btn-fill" data-user="${acc.user}" title="ملء تلقائي للصفحة">تعبئة ⚡</button>
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

    if (sites.length === 0) {
      vaultList.innerHTML = '<div style="text-align:center;color:#94a3b8;font-size:11px;padding:12px;">لا توجد حسابات مسجلة بعد</div>';
      return;
    }

    sites.forEach((site) => {
      const siteItem = document.createElement('div');
      siteItem.className = 'vault-site-item';

      const firstUrl = site.url ? site.url.split(/[,;\s]+/)[0].trim() : '';
      const fav = firstUrl ? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(firstUrl)}&sz=64` : '';
      const initial = (site.name || 'W').charAt(0).toUpperCase();
      const accountsCount = site.accounts?.length || 0;

      siteItem.innerHTML = `
        <div class="site-name-row">
          <div class="site-row-left">
            <div class="site-fav-box">
              ${fav ? `<img src="${fav}" class="site-fav-img" onerror="this.style.display='none';this.nextElementSibling.style.display='flex';" alt="">` : ''}
              <span class="site-fav-fallback" style="${fav ? 'display:none;' : 'display:flex;'}">${initial}</span>
            </div>
            <div class="site-meta">
              <span class="site-title">${site.name || 'موقع بدون اسم'}</span>
              ${firstUrl ? `<span class="site-url-sub">${firstUrl.replace(/^https?:\/\//, '').replace(/\/.*$/, '')}</span>` : ''}
            </div>
          </div>
          <span class="site-acc-count">${accountsCount} حساب ▾</span>
        </div>
        <div class="site-accounts-container" style="display:none;margin-top:8px;padding-top:6px;border-top:1px solid rgba(255,255,255,0.06);">
          ${(site.accounts || []).map((acc, aIdx) => `
            <div class="match-card" style="margin-top:4px;">
              <div class="match-user" title="${acc.user}">${acc.user}</div>
              <div class="match-actions">
                <button class="mini-btn copy-site-user" data-acc="${aIdx}" title="نسخ المستخدم">👤</button>
                <button class="mini-btn copy-site-pass" data-acc="${aIdx}" title="نسخ كلمة المرور">🔑</button>
              </div>
            </div>
          `).join('')}
        </div>
      `;

      // Toggle accordion on row click
      siteItem.querySelector('.site-name-row').addEventListener('click', () => {
        const accContainer = siteItem.querySelector('.site-accounts-container');
        accContainer.style.display = accContainer.style.display === 'none' ? 'block' : 'none';
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
  loginPass.type = loginPass.type === 'password' ? 'text' : 'password';
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
  addSiteName.value = CURRENT_DOMAIN ? CURRENT_DOMAIN.split('.')[0].toUpperCase() : '';
  addSiteUrl.value = CURRENT_TAB?.url || '';
  addUser.value = '';
  addPass.value = '';
  addNotes.value = '';
  modalAdd.classList.add('active');
  addUser.focus();
});

btnCloseModal.addEventListener('click', () => {
  modalAdd.classList.remove('active');
});

btnGenForInput.addEventListener('click', () => {
  addPass.value = generatePassword(16);
  addPass.type = 'text';
  showToast('تم توليد كلمة مرور قوية');
});

formSaveCredential.addEventListener('submit', (e) => {
  e.preventDefault();
  const siteName = addSiteName.value.trim();
  const siteUrl = addSiteUrl.value.trim();
  const user = addUser.value.trim();
  const pass = addPass.value;
  const notes = addNotes.value.trim();

  if (!siteName || !user || !pass) return;

  chrome.runtime.sendMessage({
    action: 'SAVE_CREDENTIAL',
    siteName,
    siteUrl,
    user,
    pass,
    notes
  }, (res) => {
    if (res?.success) {
      modalAdd.classList.remove('active');
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
vaultSearchInput.addEventListener('input', (e) => {
  const q = e.target.value.toLowerCase().trim();
  const items = vaultList.querySelectorAll('.vault-site-item');
  items.forEach((item) => {
    const text = item.textContent.toLowerCase();
    item.style.display = text.includes(q) ? 'block' : 'none';
  });
});

// Initialize on DOM ready
document.addEventListener('DOMContentLoaded', init);
