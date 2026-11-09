'use strict';

/* ── VIEW MODE ── */
let currentView = localStorage.getItem('rw-view') || 'grid';

function setView(v) {
  currentView = v;
  localStorage.setItem('rw-view', v);
  const el = document.getElementById('list-view');
  el.classList.remove('view-grid','view-list','view-compact');
  el.classList.add('view-' + v);
  ['grid','list','compact'].forEach(name => {
    const btn = document.getElementById('vbtn-' + name);
    if (btn) btn.classList.toggle('active', name === v);
  });
}

function initView() { setView(currentView); }

/* ── HELPERS ── */
function escHtml(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
function escAttr(s) { return String(s).replace(/'/g, '&#39;').replace(/"/g, '&quot;'); }
function showScreen(id) { document.querySelectorAll('.screen').forEach(s => s.classList.remove('active')); document.getElementById(id).classList.add('active'); }
function openModal(id) { document.getElementById(id).classList.add('open'); }
function closeModal(id) { document.getElementById(id).classList.remove('open'); }
function openSettings() { openModal('modal-settings'); }
function toast(msg, type = 'info') {
  const c = document.getElementById('toast-container');
  const icons = { success: '<path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>', error: '<path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>', info: '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/>' };
  const t = document.createElement('div'); t.className = `toast ${type}`; t.innerHTML = `<svg viewBox="0 0 24 24">${icons[type] || icons.info}</svg>${msg}`;
  c.appendChild(t); setTimeout(() => t.remove(), 3000);
}
function copyText(text) {
  navigator.clipboard.writeText(text).then(() => toast('تم النسخ ✓', 'success')).catch(() => {
    const el = document.createElement('textarea'); el.value = text; document.body.appendChild(el); el.select(); document.execCommand('copy'); el.remove(); toast('تم النسخ ✓', 'success');
  });
}
function toggleEye(id, btn) {
  const el = document.getElementById(id); const show = el.type === 'password'; el.type = show ? 'text' : 'password';
  btn.innerHTML = show ? '<svg viewBox="0 0 24 24"><path d="M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46A11.804 11.804 0 001 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z"/></svg>' : '<svg viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>';
}
function togglePw(id, pass) {
  const el = document.getElementById('pw-' + id); const shown = el.classList.contains('visible');
  el.textContent = shown ? '••••••••••••' : pass; el.classList.toggle('visible');
}
function checkStrength(p) {
  const fill = document.getElementById('s-fill'), txt = document.getElementById('s-text');
  if (!p) { fill.style.width = '0'; txt.textContent = ''; return; }
  let score = 0; if (p.length >= 8) score++; if (p.length >= 12) score++; if (/[A-Z]/.test(p)) score++; if (/[0-9]/.test(p)) score++; if (/[^a-zA-Z0-9]/.test(p)) score++;
  const lvl = [{ w: '20%', c: '#ef4444', t: 'ضعيفة جداً' }, { w: '40%', c: '#f97316', t: 'ضعيفة' }, { w: '60%', c: '#eab308', t: 'متوسطة' }, { w: '80%', c: '#22c55e', t: 'قوية' }, { w: '100%', c: '#10b981', t: 'قوية جداً ✓' }][Math.min(score, 4)];
  fill.style.width = lvl.w; fill.style.background = lvl.c; txt.textContent = lvl.t; txt.style.color = lvl.c;
}
function toggleOpt(inp, id) { document.getElementById(id).classList.toggle('active', inp.checked); }

/* ── SEARCH ── */
function toggleSearch() { const sb = document.getElementById('search-bar'); sb.classList.toggle('hidden'); if (!sb.classList.contains('hidden')) document.getElementById('search-inp').focus(); else { document.getElementById('search-inp').value = ''; renderList(); } }
function doSearch(q) { filterAndRenderList(); }
function clearSearch() { document.getElementById('search-inp').value = ''; filterAndRenderList(); }

/* ── LIST VIEW ── */
function showList(fromPopstate = false) {
  document.getElementById('detail-view').classList.remove('active');
  CUR_SITE = null;
  const sb = document.getElementById('search-bar');
  if (!sb.classList.contains('hidden')) { sb.classList.add('hidden'); document.getElementById('search-inp').value = ''; }
  // الـ FAB يرجع لإضافة موقع
  const fab = document.querySelector('.fab');
  if (fab) { fab.onclick = openAddWebsite; fab.title = 'إضافة موقع'; }
  // اعمل history.back() بس لو المستخدم ضغط زرار الرجوع الداخلي
  if (!fromPopstate && history.state && history.state.view === 'detail') {
    history.back();
  }
}

let activeTagFilter = 'all';

function renderTagsBar() {
  const bar = document.getElementById('tags-bar');
  if (!bar) return;

  const sites = VAULT?.sites || [];
  const tagsSet = new Set();
  sites.forEach(s => {
    if (s.tags) {
      s.tags.split(',').forEach(t => {
        const trimmed = t.trim();
        if (trimmed) tagsSet.add(trimmed);
      });
    }
  });

  if (tagsSet.size === 0) {
    bar.style.display = 'none';
    return;
  }

  bar.style.display = 'flex';
  const currentFilter = activeTagFilter || 'all';

  let html = `
    <button class="tag-pill ${currentFilter === 'all' ? 'active' : ''}" onclick="setTagFilter('all')">الكل</button>
  `;

  tagsSet.forEach(tag => {
    html += `
      <button class="tag-pill ${currentFilter.toLowerCase() === tag.toLowerCase() ? 'active' : ''}" onclick="setTagFilter('${escAttr(tag)}')">${escHtml(tag)}</button>
    `;
  });

  bar.innerHTML = html;
}

function setTagFilter(tag) {
  activeTagFilter = tag;
  filterAndRenderList();
  renderTagsBar();
}

function filterAndRenderList() {
  let filtered = VAULT?.sites || [];

  // تصفية بالأوسمة
  if (activeTagFilter && activeTagFilter !== 'all') {
    filtered = filtered.filter(s => {
      if (!s.tags) return false;
      return s.tags.split(',').map(t => t.trim().toLowerCase()).includes(activeTagFilter.toLowerCase());
    });
  }

  // تصفية بالبحث
  const q = document.getElementById('search-inp').value.trim().toLowerCase();
  if (q) {
    filtered = filtered.filter(s =>
      s.name.toLowerCase().includes(q) ||
      (s.url || '').toLowerCase().includes(q) ||
      s.accounts.some(a => a.user.toLowerCase().includes(q))
    );
  }

  renderList(filtered);
}

function updateHeaderStats() {
  const el = document.getElementById('hdr-stats');
  if (!el) return;
  if (!VAULT || !Array.isArray(VAULT.sites)) {
    el.textContent = '0 موقع • 0 حساب';
    return;
  }
  const s = VAULT.sites.length;
  const a = VAULT.sites.reduce((acc, site) => acc + (Array.isArray(site.accounts) ? site.accounts.length : 0), 0);
  el.textContent = `${s} موقع • ${a} حساب`;
}

function renderList(sites) {
  updateHeaderStats();
  if (!sites) {
    renderTagsBar();
    filterAndRenderList();
    return;
  }
  const sorted = [...sites].sort((a, b) => a.name.localeCompare(b.name, 'ar'));
  const el = document.getElementById('list-view');
  if (!sorted.length) { el.innerHTML = `<div class="empty-state"><div class="empty-icon"><svg viewBox="0 0 24 24"><path fill="currentColor" d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg></div><div class="empty-title">لا توجد كلمات مرور بعد</div><div class="empty-desc">اضغط + لإضافة موقع جديد</div></div>`; initView(); return; }
  el.innerHTML = sorted.map(site => {
    const firstUrl = site.url ? site.url.split(/[,;\s]+/)[0].trim() : '';
    const fav = firstUrl ? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(firstUrl)}&sz=64` : '';
    const ic = fav ? `<img src="${fav}" onerror="this.parentNode.innerHTML='<span style=font-size:20px>${escHtml(site.name.charAt(0).toUpperCase())}</span>'" alt="">` : `<span style="font-size:20px">${escHtml(site.name.charAt(0).toUpperCase())}</span>`;
    
    const tagsHtml = site.tags ? `
      <div class="card-tags">
        ${site.tags.split(',').map(t => t.trim()).filter(Boolean).map(t => `<span class="card-tag">${escHtml(t)}</span>`).join('')}
      </div>
    ` : '';

    const isChecked = selectedSiteIds.includes(site.id) ? 'checked' : '';

    return `<div class="site-card" onclick="showDetail('${site.id}')">
      <input type="checkbox" class="site-select-checkbox" onclick="event.stopPropagation();" onchange="onSiteSelectChange(this, '${site.id}')" ${isChecked} />
      <div class="site-favicon">${ic}</div>
      <div class="site-info">
        <div class="site-name">${escHtml(site.name)}</div>
        <div class="site-meta">${escHtml(site.url || '')}</div>
        ${tagsHtml}
      </div>
      <span class="site-count">${site.accounts.length} حساب</span>
    </div>`;
  }).join('');
  initView();
}

/* ── DETAIL VIEW ── */
function showDetail(id) {
  CUR_SITE = VAULT.sites.find(s => s.id === id);
  if (!CUR_SITE) return;
  document.getElementById('det-name').textContent = CUR_SITE.name;
  renderDetailHeaderUrls(CUR_SITE.name, CUR_SITE.url);
  renderDetail();
  document.getElementById('detail-view').classList.add('active');
  // الـ FAB يتحول لإضافة حساب
  const fab = document.querySelector('.fab');
  if (fab) { fab.onclick = openAddAccount; fab.title = 'إضافة حساب'; }
  // دعم زرار الرجوع في المتصفح والجهاز
  if (!history.state || history.state.view !== 'detail') {
    history.pushState({ view: 'detail', siteId: id }, '', '');
  }
}

function renderDetailHeaderUrls(name, url) {
  const urlEl = document.getElementById('det-url');
  if (!url) {
    urlEl.innerHTML = '';
    return;
  }
  const urls = url.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean);
  urlEl.innerHTML = urls.map(u => {
    let fullUrl = u;
    if (!u.startsWith('http://') && !u.startsWith('https://')) {
      fullUrl = 'https://' + u;
    }
    return `<a href="${escAttr(fullUrl)}" target="_blank" rel="noopener" class="detail-url-link" onclick="event.stopPropagation();">${escHtml(u)}</a>`;
  }).join(' • ');
}

function renderDetail() {
  const el = document.getElementById('detail-content');
  const hdr = `<div style="display:flex;gap:8px;margin-bottom:12px">
    <button class="btn btn-secondary" style="flex:1;font-size:12px" onclick="openEditSite('${CUR_SITE.id}')">✏️ تعديل الموقع</button>
    <button class="btn btn-danger" style="flex:1;font-size:12px" onclick="deleteSite('${CUR_SITE.id}')">🗑️ حذف الموقع</button>
  </div>`;
  if (!CUR_SITE.accounts.length) { el.innerHTML = hdr + `<div class="empty-state"><div class="empty-icon"><svg viewBox="0 0 24 24"><path fill="currentColor" d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg></div><div class="empty-title">لا توجد حسابات</div><div class="empty-desc">اضغط + لإضافة حساب</div></div>`; return; }
  el.innerHTML = hdr + CUR_SITE.accounts.map(a => `<div class="acc-card"><div class="acc-username clickable-username" onclick="copyText('${escAttr(a.user)}')" title="اضغط لنسخ اسم المستخدم / البريد"><svg viewBox="0 0 24 24"><path fill="currentColor" d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>${escHtml(a.user)}</div><div class="acc-password-row"><div class="acc-password" id="pw-${a.id}">••••••••••••</div><button class="acc-btn" onclick="togglePw('${a.id}','${escAttr(a.pass)}')"><svg viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg></button></div>${a.notes ? `<div style="font-size:12px;color:var(--text3);margin-top:6px">${escHtml(a.notes)}</div>` : ''}<div class="acc-actions"><button class="acc-btn copy" onclick="copyText('${escAttr(a.pass)}')"><svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>نسخ كلمة المرور</button><button class="acc-btn copy" onclick="copyText('${escAttr(a.user)}')"><svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>نسخ المستخدم</button><button class="acc-btn edit" onclick="openEditAcc('${a.id}')"><svg viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>تعديل</button><button class="acc-btn del" onclick="deleteAcc('${a.id}')"><svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>حذف</button></div></div>`).join('');
}

/* ── SITE CRUD ── */
// Category picker under the tags field: existing categories as toggle chips; typing adds a new one.
function siteTagList(value) {
  return String(value || '').split(',').map(t => t.trim()).filter(Boolean);
}
function renderSiteTagPicker() {
  const input = document.getElementById('site-tags');
  const box = document.getElementById('site-tags-picker');
  if (!input || !box) return;
  const chosen = siteTagList(input.value).map(t => t.toLowerCase());
  const all = new Map();
  (VAULT?.sites || []).forEach(s => siteTagList(s.tags).forEach(t => { if (!all.has(t.toLowerCase())) all.set(t.toLowerCase(), t); }));
  box.innerHTML = '';
  [...all.values()].sort((a, b) => a.localeCompare(b, 'ar')).forEach(tag => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'tag-pill' + (chosen.includes(tag.toLowerCase()) ? ' active' : '');
    chip.textContent = tag;
    chip.onclick = () => {
      const list = siteTagList(input.value);
      const i = list.findIndex(t => t.toLowerCase() === tag.toLowerCase());
      if (i >= 0) list.splice(i, 1); else list.push(tag);
      input.value = list.join(', ');
      renderSiteTagPicker();
    };
    box.appendChild(chip);
  });
}
function openAddWebsite() {
  EDIT_SITE = null;
  document.getElementById('modal-site-title').textContent = 'إضافة موقع';
  document.getElementById('site-name').value = '';
  document.getElementById('site-url').value = '';
  if (document.getElementById('site-tags')) document.getElementById('site-tags').value = '';
  renderSiteTagPicker();
  document.getElementById('site-err').classList.remove('show');
  openModal('modal-site');
}
function openEditSite(id) {
  EDIT_SITE = VAULT.sites.find(s => s.id === id);
  if (!EDIT_SITE) return;
  document.getElementById('modal-site-title').textContent = 'تعديل الموقع';
  document.getElementById('site-name').value = EDIT_SITE.name;
  document.getElementById('site-url').value = EDIT_SITE.url || '';
  if (document.getElementById('site-tags')) document.getElementById('site-tags').value = EDIT_SITE.tags || '';
  renderSiteTagPicker();
  document.getElementById('site-err').classList.remove('show');
  openModal('modal-site');
}
async function saveSite() {
  const name = document.getElementById('site-name').value.trim(),
        url = document.getElementById('site-url').value.trim(),
        tags = document.getElementById('site-tags') ? document.getElementById('site-tags').value.trim() : '';
  const err = document.getElementById('site-err');
  if (!name) { err.textContent = 'اسم الموقع مطلوب'; err.classList.add('show'); return; }
  
  if (EDIT_SITE) {
    EDIT_SITE.name = name;
    EDIT_SITE.url = url;
    EDIT_SITE.tags = tags;
    if (CUR_SITE?.id === EDIT_SITE.id) {
      document.getElementById('det-name').textContent = name;
      renderDetailHeaderUrls(name, url);
    }
  } else {
    const ns = { id: Date.now().toString() + Math.random().toString(36).slice(2), name, url, tags, accounts: [] };
    VAULT.sites.push(ns);
    await saveVault();
    renderList();
    closeModal('modal-site');
    toast('تم إضافة الموقع! أضف حسابك الأول الآن', 'success');
    showDetail(ns.id);
    setTimeout(() => openAddAccount(), 200);
    EDIT_SITE = null;
    return;
  }
  await saveVault();
  renderList();
  closeModal('modal-site');
  toast('تم التحديث', 'success');
  EDIT_SITE = null;
}
async function deleteSite(id) { if (!confirm('حذف هذا الموقع وكل حساباته؟')) return; VAULT.sites = VAULT.sites.filter(s => s.id !== id); await saveVault(); renderList(); showList(); toast('تم الحذف', 'info'); }

/* ── SITE MERGE (PWA) ── */
let currentMergeSourceId = null;

function openMergeSite(id) {
  const source = VAULT.sites.find(s => s.id === id);
  if (!source) return;

  currentMergeSourceId = id;
  document.getElementById('merge-src-name').value = source.name;
  document.getElementById('merge-new-name').value = '';
  document.getElementById('merge-err').classList.remove('show');
  
  const previewBox = document.getElementById('merge-preview');
  previewBox.style.display = 'none';

  const select = document.getElementById('merge-tgt-select');
  const targets = VAULT.sites.filter(s => s.id !== id);
  select.innerHTML = '<option value="">-- اختر الموقع المستهدف --</option>' +
    targets.map(t => `<option value="${t.id}">${escHtml(t.name)} (${escHtml(t.url || 'لا يوجد رابط')})</option>`).join('');

  openModal('modal-merge');
}

// Event listener for target select change
document.getElementById('merge-tgt-select').addEventListener('change', (e) => {
  const targetId = e.target.value;
  const previewBox = document.getElementById('merge-preview');
  
  if (!targetId) {
    previewBox.style.display = 'none';
    return;
  }

  const source = VAULT.sites.find(s => s.id === currentMergeSourceId);
  const target = VAULT.sites.find(s => s.id === targetId);

  if (!source || !target) return;

  document.getElementById('merge-new-name').value = target.name;

  // Compute combined URLs
  const getUniqueUrls = (u1, u2) => {
    const arr1 = u1 ? u1.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
    const arr2 = u2 ? u2.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
    return [...new Set([...arr1, ...arr2])];
  };
  const uniqueUrls = getUniqueUrls(source.url, target.url);
  document.getElementById('merge-pre-urls').textContent = 'الروابط المدمجة: ' + (uniqueUrls.length > 0 ? uniqueUrls.join(', ') : 'لا يوجد');

  // Compute unique accounts (de-duped by user & password)
  const targetKeys = target.accounts.map(a => a.user + ':::' + a.pass);
  let duplicateCount = 0;
  source.accounts.forEach(sa => {
    if (targetKeys.includes(sa.user + ':::' + sa.pass)) {
      duplicateCount++;
    }
  });
  const totalCount = target.accounts.length + source.accounts.length - duplicateCount;
  document.getElementById('merge-pre-accounts').textContent = `عدد الحسابات بعد الدمج: ${totalCount} حساب (سيتم حذف المكرر)`;

  previewBox.style.display = 'block';
});

async function saveMerge() {
  const targetId = document.getElementById('merge-tgt-select').value;
  const newName = document.getElementById('merge-new-name').value.trim();
  const err = document.getElementById('merge-err');
  
  err.classList.remove('show');

  if (!targetId) {
    err.textContent = 'يرجى اختيار الموقع المستهدف للدمج فيه';
    err.classList.add('show');
    return;
  }

  const sourceIndex = VAULT.sites.findIndex(s => s.id === currentMergeSourceId);
  const targetIndex = VAULT.sites.findIndex(s => s.id === targetId);

  if (sourceIndex === -1 || targetIndex === -1) {
    err.textContent = 'حدث خطأ: لم يتم العثور على المواقع';
    err.classList.add('show');
    return;
  }

  const source = VAULT.sites[sourceIndex];
  const target = VAULT.sites[targetIndex];

  const btn = document.getElementById('merge-save-btn');
  btn.disabled = true;
  btn.textContent = 'جاري الدمج...';

  try {
    // 1. Combine URLs
    const getUniqueUrls = (u1, u2) => {
      const arr1 = u1 ? u1.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
      const arr2 = u2 ? u2.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
      return [...new Set([...arr1, ...arr2])].join(', ');
    };
    target.url = getUniqueUrls(source.url, target.url);

    // 2. Combine Tags
    const getUniqueTags = (t1, t2) => {
      const arr1 = t1 ? t1.split(/[,;\s]+/).map(t => t.trim()).filter(Boolean) : [];
      const arr2 = t2 ? t2.split(/[,;\s]+/).map(t => t.trim()).filter(Boolean) : [];
      return [...new Set([...arr1, ...arr2])].join(', ');
    };
    target.tags = getUniqueTags(source.tags, target.tags);

    // 3. Combine Accounts (de-duped)
    const targetKeys = target.accounts.map(a => a.user + ':::' + a.pass);
    source.accounts.forEach(sa => {
      if (!targetKeys.includes(sa.user + ':::' + sa.pass)) {
        target.accounts.push(sa);
      }
    });

    target.name = newName || target.name;

    // Remove source site
    VAULT.sites.splice(sourceIndex, 1);

    await saveVault();
    closeModal('modal-merge');
    showList();
    renderList();
    toast('✅ تم دمج المواقع وحساباتها بنجاح', 'success');
  } catch (e) {
    err.textContent = 'خطأ: ' + e.message;
    err.classList.add('show');
  } finally {
    btn.disabled = false;
    btn.textContent = 'تأكيد الدمج';
  }
}

/* ── ACCOUNT CRUD ── */
function openAddAccount() { EDIT_ACC = null; document.getElementById('modal-acc-title').textContent = 'إضافة حساب'; document.getElementById('acc-user').value = ''; document.getElementById('acc-pass').value = ''; document.getElementById('acc-notes').value = ''; document.getElementById('acc-err').classList.remove('show'); openModal('modal-acc'); }
function openEditAcc(id) { if (!CUR_SITE) return; EDIT_ACC = CUR_SITE.accounts.find(a => a.id === id); if (!EDIT_ACC) return; document.getElementById('modal-acc-title').textContent = 'تعديل الحساب'; document.getElementById('acc-user').value = EDIT_ACC.user; document.getElementById('acc-pass').value = EDIT_ACC.pass; document.getElementById('acc-notes').value = EDIT_ACC.notes || ''; document.getElementById('acc-err').classList.remove('show'); openModal('modal-acc'); }
async function saveAccount() {
  const user = document.getElementById('acc-user').value.trim(), pass = document.getElementById('acc-pass').value, notes = document.getElementById('acc-notes').value.trim();
  const err = document.getElementById('acc-err'); if (!user || !pass) { err.textContent = 'اسم المستخدم وكلمة المرور مطلوبان'; err.classList.add('show'); return; }
  if (EDIT_ACC) { EDIT_ACC.user = user; EDIT_ACC.pass = pass; EDIT_ACC.notes = notes; }
  else { if (!CUR_SITE) return; CUR_SITE.accounts.push({ id: Date.now().toString(), user, pass, notes }); }
  await saveVault(); renderDetail(); closeModal('modal-acc'); toast(EDIT_ACC ? 'تم التحديث' : 'تم الإضافة', 'success'); EDIT_ACC = null;
}
async function deleteAcc(id) { if (!CUR_SITE || !confirm('حذف هذا الحساب؟')) return; CUR_SITE.accounts = CUR_SITE.accounts.filter(a => a.id !== id); await saveVault(); renderDetail(); toast('تم الحذف', 'info'); }

/* ── PASSWORD GENERATOR ── */
function genPass() {
  const len = parseInt(document.getElementById('gen-len').value);
  let chars = '';
  if (document.querySelector('#opt-upper input').checked) chars += 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  if (document.querySelector('#opt-lower input').checked) chars += 'abcdefghijklmnopqrstuvwxyz';
  if (document.querySelector('#opt-num input').checked) chars += '0123456789';
  if (document.querySelector('#opt-sym input').checked) chars += '!@#$%^&*()-_=+[]{}|;:,.<>?';
  if (!chars) { document.getElementById('gen-result').textContent = 'اختر نوعاً واحداً على الأقل'; return; }
  const arr = new Uint32Array(len); crypto.getRandomValues(arr);
  document.getElementById('gen-result').textContent = Array.from(arr, n => chars[n % chars.length]).join('');
}
function openGenPass() { genPass(); openModal('modal-gen'); }
function useGenPass() { const p = document.getElementById('gen-result').textContent; if (p && p !== 'اختر نوعاً واحداً على الأقل') { document.getElementById('acc-pass').value = p; closeModal('modal-gen'); } }

/* ── CLOSE MODALS ON BACKDROP ── */
document.querySelectorAll('.modal-overlay').forEach(o => o.addEventListener('click', e => { if (e.target === o) o.classList.remove('open'); }));

/* ── BROWSER / DEVICE BACK BUTTON ── */
window.addEventListener('popstate', (e) => {
  // لو في مودال مفتوح، اقفله
  const openMod = document.querySelector('.modal-overlay.open');
  if (openMod) { openMod.classList.remove('open'); history.pushState(null, '', ''); return; }
  // لو في صفحة التفاصيل، ارجع للقائمة
  if (document.getElementById('detail-view').classList.contains('active')) {
    showList(true); // true = قادم من popstate، لا تعمل history.back() تاني
  }
});

/* ── BATCH ACTIONS LOGIC (PWA) ── */
let selectedSiteIds = [];

function onSiteSelectChange(checkbox, id) {
  if (checkbox.checked) {
    if (!selectedSiteIds.includes(id)) {
      selectedSiteIds.push(id);
    }
  } else {
    selectedSiteIds = selectedSiteIds.filter(item => item !== id);
  }
  updateBatchBar();
}

function updateBatchBar() {
  const bar = document.getElementById('batch-actions-bar');
  const countSpan = document.getElementById('batch-count');
  if (!bar || !countSpan) return;

  if (selectedSiteIds.length > 0) {
    bar.classList.remove('hidden');
    countSpan.textContent = `تم تحديد ${selectedSiteIds.length} موقع`;
  } else {
    bar.classList.add('hidden');
  }
}

function cancelBatchSelection() {
  selectedSiteIds = [];
  document.querySelectorAll('.site-select-checkbox').forEach(cb => cb.checked = false);
  updateBatchBar();
}

async function deleteBatchSites() {
  if (!selectedSiteIds.length) return;
  if (!confirm(`هل أنت متأكد من حذف ${selectedSiteIds.length} موقع بجميع حساباتها؟`)) return;

  VAULT.sites = VAULT.sites.filter(s => !selectedSiteIds.includes(s.id));
  await saveVault();
  cancelBatchSelection();
  renderList();
  toast('تم حذف المواقع المحددة بنجاح', 'success');
}

async function openBatchTag() {
  if (!selectedSiteIds.length) return;
  const tag = prompt("أدخل اسم الوسم (الجروب) لتعيينه للمواقع المحددة:");
  if (tag === null) return; // cancelled
  const trimmedTag = tag.trim();
  if (!trimmedTag) {
    toast('الوسم لا يمكن أن يكون فارغاً', 'error');
    return;
  }

  // Add the tag to all selected sites
  selectedSiteIds.forEach(id => {
    const site = VAULT.sites.find(s => s.id === id);
    if (site) {
      const existingTags = site.tags ? site.tags.split(',').map(t => t.trim()).filter(Boolean) : [];
      if (!existingTags.map(t => t.toLowerCase()).includes(trimmedTag.toLowerCase())) {
        existingTags.push(trimmedTag);
        site.tags = existingTags.join(', ');
      }
    }
  });

  await saveVault();
  cancelBatchSelection();
  renderList();
  toast('تم إضافة الوسم للمواقع المحددة بنجاح', 'success');
}

function startBatchMerge() {
  if (selectedSiteIds.length < 2) {
    toast('يرجى تحديد موقعين على الأقل للدمج', 'error');
    return;
  }

  // Open the merge modal
  // The first site will be the target by default, others will merge into it
  const targetId = selectedSiteIds[0];
  const target = VAULT.sites.find(s => s.id === targetId);
  const sources = VAULT.sites.filter(s => selectedSiteIds.includes(s.id) && s.id !== targetId);

  // We can let the user pick which of the selected sites should be the target
  const select = document.getElementById('merge-tgt-select');
  select.innerHTML = selectedSiteIds.map(id => {
    const s = VAULT.sites.find(x => x.id === id);
    return `<option value="${s.id}">${escHtml(s.name)}</option>`;
  }).join('');
  
  // Set target selection to the first one
  select.value = targetId;

  // Set source names
  const sourceNames = sources.map(s => s.name).join(' + ');
  document.getElementById('merge-src-name').value = sourceNames;
  document.getElementById('merge-new-name').value = target.name;
  
  document.getElementById('merge-err').classList.remove('show');
  
  // Update preview
  updateBatchMergePreview(targetId, sources);

  // When select changes, update preview
  select.onchange = (e) => {
    const tId = e.target.value;
    const t = VAULT.sites.find(s => s.id === tId);
    const srcs = VAULT.sites.filter(s => selectedSiteIds.includes(s.id) && s.id !== tId);
    document.getElementById('merge-src-name').value = srcs.map(s => s.name).join(' + ');
    document.getElementById('merge-new-name').value = t.name;
    updateBatchMergePreview(tId, srcs);
  };

  // Override the save button action to do batch save merge
  const btn = document.getElementById('merge-save-btn');
  btn.onclick = async () => {
    const tId = select.value;
    const tSite = VAULT.sites.find(s => s.id === tId);
    const srcs = VAULT.sites.filter(s => selectedSiteIds.includes(s.id) && s.id !== tId);
    const newName = document.getElementById('merge-new-name').value.trim();

    btn.disabled = true;
    btn.textContent = 'جاري الدمج...';

    try {
      // 1. Combine URLs
      const getUniqueUrls = (u1, u2) => {
        const arr1 = u1 ? u1.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
        const arr2 = u2 ? u2.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
        return [...new Set([...arr1, ...arr2])].join(', ');
      };
      
      // 2. Combine Tags
      const getUniqueTags = (t1, t2) => {
        const arr1 = t1 ? t1.split(/[,;\s]+/).map(t => t.trim()).filter(Boolean) : [];
        const arr2 = t2 ? t2.split(/[,;\s]+/).map(t => t.trim()).filter(Boolean) : [];
        return [...new Set([...arr1, ...arr2])].join(', ');
      };

      srcs.forEach(src => {
        tSite.url = getUniqueUrls(tSite.url, src.url);
        tSite.tags = getUniqueTags(tSite.tags, src.tags);

        // Combine Accounts (de-duped)
        const targetKeys = tSite.accounts.map(a => a.user + ':::' + a.pass);
        src.accounts.forEach(sa => {
          if (!targetKeys.includes(sa.user + ':::' + sa.pass)) {
            tSite.accounts.push(sa);
          }
        });
      });

      tSite.name = newName || tSite.name;

      // Remove source sites
      VAULT.sites = VAULT.sites.filter(s => !selectedSiteIds.includes(s.id) || s.id === tId);

      await saveVault();
      closeModal('modal-merge');
      cancelBatchSelection();
      showList();
      renderList();
      toast('✅ تم دمج المواقع وحساباتها بنجاح', 'success');
    } catch (e) {
      document.getElementById('merge-err').textContent = 'خطأ: ' + e.message;
      document.getElementById('merge-err').classList.add('show');
    } finally {
      btn.disabled = false;
      btn.textContent = 'تأكيد الدمج';
      // Revert standard onclick behavior
      btn.onclick = saveMerge;
    }
  };

  openModal('modal-merge');
}

function updateBatchMergePreview(targetId, sources) {
  const target = VAULT.sites.find(s => s.id === targetId);
  const previewBox = document.getElementById('merge-preview');
  previewBox.style.display = 'block';

  // Compute combined URLs
  const getUniqueUrls = (u1, u2) => {
    const arr1 = u1 ? u1.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
    const arr2 = u2 ? u2.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
    return [...new Set([...arr1, ...arr2])];
  };

  let combinedUrls = target.url ? target.url.split(/[,;\s]+/).map(u => u.trim()).filter(Boolean) : [];
  sources.forEach(src => {
    combinedUrls = getUniqueUrls(combinedUrls.join(','), src.url);
  });

  document.getElementById('merge-pre-urls').textContent = 'الروابط المدمجة: ' + (combinedUrls.length > 0 ? combinedUrls.join(', ') : 'لا يوجد');

  // Compute unique accounts count
  let targetAccounts = [...target.accounts];
  sources.forEach(src => {
    const targetKeys = targetAccounts.map(a => a.user + ':::' + a.pass);
    src.accounts.forEach(sa => {
      if (!targetKeys.includes(sa.user + ':::' + sa.pass)) {
        targetAccounts.push(sa);
      }
    });
  });

  document.getElementById('merge-pre-accounts').textContent = `عدد الحسابات بعد الدمج: ${targetAccounts.length} حساب (سيتم حذف المكرر)`;
}


