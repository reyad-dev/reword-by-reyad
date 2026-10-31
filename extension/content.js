// ReWord Autofill Content Script (Manifest V3)
(() => {
  'use strict';

  // Shield Icon SVG
  const SHIELD_SVG = `<svg viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z"/></svg>`;

  let activeDropdown = null;
  // Map of active fields to their badge controller: Map<HTMLInputElement, { btn, updatePosition, destroy }>
  const activeBadges = new Map();
  const inputs = (root = document) => {
    const found = Array.from(root.querySelectorAll('input'));
    for (const element of root.querySelectorAll('*')) {
      if (element.shadowRoot && !element.classList.contains('reword-save-host')) found.push(...inputs(element.shadowRoot));
    }
    return found;
  };
  const isInternal = field => Boolean(field?.closest?.('[class^="reword-"], [class*=" reword-"]'));
  function isUsername(field) {
    if (!field || isInternal(field) || field.disabled || field.readOnly) return false;
    const info = [field.name, field.id, field.autocomplete, field.placeholder, field.getAttribute('aria-label')].join(' ');
    if (/search|query|otp|one-time|verification|confirm|first.?name|last.?name|بحث|تحقق/i.test(info)) return false;
    return field.type === 'email' || /username|user.?id|email|e-mail|login|phone|mobile|بريد|اسم.?المستخدم|هاتف/i.test(info);
  }
  function passwordFields(root = document) {
    return inputs(root).filter(field => !isInternal(field) && !field.disabled &&
      !/one-time-code|otp|verification/i.test([field.autocomplete, field.name, field.id].join(' ')) &&
      (field.type === 'password' || /^(current-password|new-password)$/.test(field.autocomplete)) && isFieldVisible(field));
  }
  function isNewPassword(field) {
    return field.autocomplete === 'new-password' || /(new|confirm|repeat|create)[_ -]*(password|passwd|pwd)|(password|passwd|pwd)[_ -]*(new|confirm|repeat)/i.test(
      [field.name, field.id].join(' '));
  }
  function associatedPassword(field) {
    return passwordFields(field.form || field.getRootNode())[0] || null;
  }

  // 5-Layer Bulletproof visibility check
  function isFieldVisible(field) {
    if (!field || !field.isConnected || field.disabled || field.readOnly) return false;
    if (field.type === 'hidden') return false;

    // Layer 1: Physical geometry & dimensions
    const rect = field.getBoundingClientRect();
    if (rect.width < 14 || rect.height < 14) return false;

    // Layer 2: Viewport bounds
    if (rect.bottom < -200 || rect.top > (window.innerHeight + 200) || rect.right < -200 || rect.left > (window.innerWidth + 200)) {
      return false;
    }

    // Layer 3: Native Chrome checkVisibility if supported
    if (typeof field.checkVisibility === 'function') {
      try {
        if (!field.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true, contentVisibilityAuto: true })) {
          return false;
        }
      } catch (_) {}
    }

    // Layer 4: Full Ancestor Tree Walk (detects hidden parent screens, modals, opacity, pointer-events)
    let curr = field;
    while (curr && curr !== document.body && curr !== document.documentElement) {
      const s = window.getComputedStyle(curr);
      if (s.display === 'none') return false;
      if (s.visibility === 'hidden' || s.visibility === 'collapse') return false;
      if (parseFloat(s.opacity) < 0.1) return false;
      if (s.pointerEvents === 'none') return false;
      if (curr.hasAttribute('hidden') || curr.getAttribute('aria-hidden') === 'true') return false;
      curr = curr.parentElement;
    }

    // Layer 5: Hit-testing at center point to ensure field is not obscured by an overlay, loading screen, or dialog
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    if (cx >= 0 && cx <= window.innerWidth && cy >= 0 && cy <= window.innerHeight) {
      const hit = field.getRootNode().elementFromPoint?.(cx, cy) || document.elementFromPoint(cx, cy);
      if (hit && hit !== field && !field.contains(hit) && !hit.contains(field) && !hit.classList.contains('reword-autofill-btn')) {
        return false;
      }
    }

    return true;
  }

  // Remove single badge
  function removeBadge(field) {
    const entry = activeBadges.get(field);
    if (entry) {
      entry.destroy();
      activeBadges.delete(field);
    }
  }

  // Completely wipe all badges immediately (e.g. on login or submission)
  function clearAllBadges() {
    closeDropdown();
    for (const [field, entry] of Array.from(activeBadges.entries())) {
      entry.destroy();
    }
    activeBadges.clear();
    const allBtns = document.querySelectorAll('.reword-autofill-btn');
    allBtns.forEach(b => b.remove());
  }

  // Clean all badges whose inputs are no longer visible or removed from DOM
  function cleanDeadBadges() {
    for (const [field, entry] of Array.from(activeBadges.entries())) {
      if (!isFieldVisible(field)) {
        removeBadge(field);
      } else {
        entry.updatePosition();
      }
    }

    // Purge any orphan shield buttons in the DOM not tracked in activeBadges
    const allBtns = document.querySelectorAll('.reword-autofill-btn');
    const trackedBtns = new Set(Array.from(activeBadges.values()).map(e => e.btn));
    allBtns.forEach(btn => {
      if (!trackedBtns.has(btn)) {
        btn.remove();
      }
    });
  }

  // Helper to trigger realistic input events for React, Vue, etc.
  function setFieldValue(field, value) {
    if (!field) return;
    field.focus();
    
    // Support React 16+ value tracker
    const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    if (nativeSetter) {
      nativeSetter.call(field, value);
    } else {
      field.value = value;
    }

    field.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
    field.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
    field.classList.add('reword-filled-success');
    setTimeout(() => field.classList.remove('reword-filled-success'), 1500);
  }

  // Find the username field associated with a password field
  function findUsernameField(passField) {
    const candidates = inputs(passField.form || passField.getRootNode()).filter(field =>
      field !== passField && field.type !== 'password' && !isInternal(field) && isFieldVisible(field) &&
      /^(text|email|tel|number)$/.test(field.type));
    const explicit = candidates.filter(isUsername);
    if (explicit.length) return explicit.find(field => field.type === 'email') || explicit[0];
    const before = candidates.filter(field => field.compareDocumentPosition(passField) & Node.DOCUMENT_POSITION_FOLLOWING);
    // An unlabelled text input immediately before the password is a useful fallback in a login form.
    return before.filter(field => !/search|otp|code|first.?name|last.?name|address/i.test(
      [field.name, field.id, field.autocomplete, field.placeholder].join(' '))).pop() || null;
  }

  // Remove existing dropdown
  function closeDropdown() {
    if (activeDropdown) {
      activeDropdown.remove();
      activeDropdown = null;
    }
  }

  // Position and attach the ReWord badge inside the input field
  function attachBadge(inputField) {
    if (!isFieldVisible(inputField)) return;
    if (activeBadges.has(inputField)) {
      activeBadges.get(inputField).updatePosition();
      return;
    }

    const btn = document.createElement('div');
    btn.className = 'reword-autofill-btn';
    btn.innerHTML = SHIELD_SVG;
    btn.title = 'ReWord Autofill';
    document.body.appendChild(btn);

    function updateBadgePosition() {
      if (!isFieldVisible(inputField)) {
        removeBadge(inputField);
        return;
      }

      const rect = inputField.getBoundingClientRect();
      btn.style.display = 'flex';
      
      const isRtl = window.getComputedStyle(inputField).direction === 'rtl';
      const top = window.scrollY + rect.top + (rect.height - 24) / 2;
      const left = isRtl
        ? window.scrollX + rect.left + 6
        : window.scrollX + rect.right - 30;

      btn.style.top = `${top}px`;
      btn.style.left = `${left}px`;
    }

    // Attach ResizeObserver to field if available to track field removal/shrinking instantly
    let ro = null;
    if (typeof ResizeObserver !== 'undefined') {
      try {
        ro = new ResizeObserver(() => {
          updateBadgePosition();
        });
        ro.observe(inputField);
      } catch (_) {}
    }

    const onScrollOrResize = () => updateBadgePosition();
    window.addEventListener('resize', onScrollOrResize, { passive: true });
    window.addEventListener('scroll', onScrollOrResize, { passive: true });

    btn.addEventListener('mousedown', (e) => {
      e.preventDefault();
      e.stopPropagation();
      openAutofillDropdown(inputField, btn);
    });

    const destroy = () => {
      window.removeEventListener('resize', onScrollOrResize);
      window.removeEventListener('scroll', onScrollOrResize);
      if (ro) {
        ro.disconnect();
        ro = null;
      }
      if (btn.parentNode) {
        btn.parentNode.removeChild(btn);
      }
    };

    activeBadges.set(inputField, {
      btn,
      updatePosition: updateBadgePosition,
      destroy
    });

    updateBadgePosition();
  }

  // Open Autofill Dropdown
  function openAutofillDropdown(field, anchorBtn) {
    closeDropdown();

    chrome.runtime.sendMessage({
      action: 'GET_MATCHING_CREDENTIALS',
      url: window.location.href
    }, (res) => {
      if (chrome.runtime.lastError || !res) return;

      const dropdown = document.createElement('div');
      dropdown.className = 'reword-dropdown';

      const rect = field.getBoundingClientRect();
      dropdown.style.top = `${window.scrollY + rect.bottom + 6}px`;
      dropdown.style.left = `${window.scrollX + rect.left}px`;
      dropdown.style.width = `${Math.max(rect.width, 280)}px`;

      if (res.isLocked) {
        dropdown.innerHTML = `
          <div class="reword-dropdown-header">
            <div class="reword-dropdown-brand">${SHIELD_SVG}<span>ReWord</span></div>
            <span>الخزينة مقفلة</span>
          </div>
          <div class="reword-dropdown-locked">
            <div>🔒 الخزينة مقفلة حالياً</div>
            <div style="font-size:11px;color:#a1a1aa;margin-top:4px;">افتح إضافة ReWord من شريط المتصفح لإلغاء القفل</div>
          </div>
        `;
      } else {
        const hasMatches = res.matches && res.matches.length > 0;
        const allList = res.allAccounts || [];

        dropdown.innerHTML = `
          <div class="reword-dropdown-header">
            <div class="reword-dropdown-brand">${SHIELD_SVG}<span>ReWord</span></div>
            <span>${res.domain || 'الموقع الحالي'}</span>
          </div>
          ${!hasMatches ? `
            <div class="reword-empty-notice">
              لا توجد حسابات مسجلة لهذا الموقع
            </div>
          ` : ''}
          <div class="reword-dropdown-search-wrap">
            <input type="text" class="reword-search-input" placeholder="🔍 ابحث في الخزينة عن أي حساب..." ${!hasMatches ? 'autofocus' : ''}>
          </div>
          <div class="reword-search-results"></div>
          <div class="reword-dropdown-footer">
            <button type="button" class="reword-btn-quick-save">+ حفظ بيانات هذا الموقع في ReWord</button>
          </div>
        `;

        const resultsContainer = dropdown.querySelector('.reword-search-results');
        const searchInput = dropdown.querySelector('.reword-search-input');
        const saveBtn = dropdown.querySelector('.reword-btn-quick-save');

        function fillCreds(acc) {
          const usernameField = field.type === 'password' ? findUsernameField(field) : field;
          const passwordField = field.type === 'password' ? field : associatedPassword(field);

          if (usernameField && acc.user) setFieldValue(usernameField, acc.user);
          if (passwordField && !isNewPassword(passwordField) && acc.pass) setFieldValue(passwordField, acc.pass);
          closeDropdown();
        }

        function renderList(accounts) {
          resultsContainer.innerHTML = '';
          if (!accounts || accounts.length === 0) {
            resultsContainer.innerHTML = '<div style="text-align:center;color:#94a3b8;font-size:11px;padding:12px;">لا توجد حسابات مطابقة للبحث</div>';
            return;
          }
          accounts.forEach((acc) => {
            const item = document.createElement('div');
            item.className = 'reword-dropdown-item';
            const firstUrl = acc.siteUrl ? acc.siteUrl.split(/[,;\s]+/)[0].trim() : '';
            const fav = firstUrl ? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(firstUrl)}&sz=64` : '';
            const letter = (acc.siteName || acc.user || 'W').charAt(0).toUpperCase();

            item.innerHTML = `
              <div class="reword-item-avatar">
                ${fav ? `<img src="${fav}" class="reword-fav-img" onerror="this.style.display='none';this.nextElementSibling.style.display='block';" alt="">` : ''}
                <span style="${fav ? 'display:none;' : ''}">${letter}</span>
              </div>
              <div class="reword-item-info">
                <span class="reword-item-user">${acc.user || 'بدون اسم مستخدم'}</span>
                <span class="reword-item-sub">${acc.siteName || acc.siteUrl || res.domain}</span>
              </div>
            `;

            item.addEventListener('mousedown', (e) => {
              e.preventDefault();
              e.stopPropagation();
              fillCreds(acc);
            });

            resultsContainer.appendChild(item);
          });
        }

        // Initially render matches if present, or all accounts
        renderList(hasMatches ? res.matches : allList);

        // Filter on search input
        searchInput.addEventListener('input', (e) => {
          const q = e.target.value.trim().toLowerCase();
          if (!q) {
            renderList(hasMatches ? res.matches : allList);
            return;
          }
          const filtered = allList.filter((a) =>
            (a.user && a.user.toLowerCase().includes(q)) ||
            (a.siteName && a.siteName.toLowerCase().includes(q)) ||
            (a.siteUrl && a.siteUrl.toLowerCase().includes(q))
          );
          renderList(filtered);
        });

        // Quick Save Button
        saveBtn.addEventListener('mousedown', (e) => {
          e.preventDefault();
          e.stopPropagation();
          const usernameField = field.type === 'password' ? findUsernameField(field) : field;
          const passwordField = field.type === 'password' ? field : associatedPassword(field);

          const userVal = usernameField ? usernameField.value.trim() : '';
          const passVal = passwordField ? passwordField.value : '';

          if (!userVal && !passVal) {
            alert('يرجى كتابة اسم المستخدم أو كلمة المرور في الصفحة أولاً ليتم حفظها');
            return;
          }

          chrome.runtime.sendMessage({
            action: 'SAVE_CREDENTIAL',
            siteName: res.domain || window.location.hostname,
            siteUrl: window.location.origin,
            user: userVal,
            pass: passVal,
            notes: ''
          }, (saveRes) => {
            if (saveRes?.success) {
              alert('✓ تم حفظ بيانات الدخول في ReWord بنجاح!');
              closeDropdown();
            } else {
              alert('تعذر الحفظ: ' + (saveRes?.error || 'حدث خطأ'));
            }
          });
        });
      }

      document.body.appendChild(dropdown);
      activeDropdown = dropdown;
    });
  }

  // Scan page for login fields
  function scanPage() {
    cleanDeadBadges();

    const passInputs = passwordFields();
    passInputs.forEach((passInput) => {
      if (isFieldVisible(passInput)) {
        attachBadge(passInput);
        const userInput = findUsernameField(passInput);
        if (userInput && isFieldVisible(userInput)) {
          attachBadge(userInput);
        }
      }
    });
    inputs().filter(field => isUsername(field) && isFieldVisible(field)).forEach(attachBadge);
  }

  // Capture only credential fields; consent is always required before writing to the vault.
  let saveHost = null;
  let captureTimer;
  let lastCapture = '';
  let dismissedCapture = '';
  let previousUsername = '';
  function showSavePrompt(state) {
    if (!state?.pending) { saveHost?.remove(); saveHost = null; return; }
    if (saveHost?.isConnected) return;
    saveHost = document.createElement('div');
    saveHost.className = 'reword-save-host';
    const shadow = saveHost.attachShadow({ mode: 'closed' });
    shadow.innerHTML = `<style>
      :host{all:initial;position:fixed;top:16px;right:16px;z-index:2147483647;direction:rtl}
      section{box-sizing:border-box;width:min(350px,calc(100vw - 32px));padding:18px;background:#111827;color:#f8fafc;border:1px solid #64748b;border-radius:14px;box-shadow:0 12px 40px #0006;font:14px system-ui}
      strong{font-size:16px}p{overflow-wrap:anywhere;line-height:1.6}button{cursor:pointer;border:0;border-radius:8px;padding:9px 16px;margin-left:8px;background:#7c3aed;color:white;font:inherit}button:last-child{background:#334155}small{display:block;margin-top:10px;color:#cbd5e1}
      </style><section role="dialog" aria-label="حفظ بيانات الدخول في ReWord">
      <strong></strong><p></p><button type="button">حفظ</button><button type="button">ليس الآن</button><small></small></section>`;
    shadow.querySelector('strong').textContent = state.update ? 'تحديث كلمة المرور في ReWord؟' : 'حفظ بيانات الدخول في ReWord؟';
    shadow.querySelector('p').textContent = `${state.domain} — ${state.user || 'بدون اسم مستخدم'}`;
    const [save, dismiss] = shadow.querySelectorAll('button');
    save.textContent = state.update ? 'تحديث' : 'حفظ';
    const status = shadow.querySelector('small');
    if (state.isLocked) status.textContent = 'افتح إضافة ReWord لإلغاء قفل الخزنة، ثم اضغط حفظ.';
    save.addEventListener('click', () => {
      save.disabled = true;
      chrome.runtime.sendMessage({ action: 'CONFIRM_PENDING_CREDENTIAL' }, result => {
        save.disabled = false;
        if (chrome.runtime.lastError) { status.textContent = 'أعد تحميل الإضافة ثم حاول مرة أخرى.'; return; }
        if (result?.success) {
          saveHost?.remove(); saveHost = null;
        } else status.textContent = result?.error === 'vault_locked'
          ? 'افتح إضافة ReWord لإلغاء قفل الخزنة، ثم اضغط حفظ.' : 'تعذر الحفظ. أعد كتابة البيانات وحاول مرة أخرى.';
      });
    });
    dismiss.addEventListener('click', () => {
      dismissedCapture = lastCapture;
      saveHost?.remove(); saveHost = null;
      chrome.runtime.sendMessage({ action: 'DISMISS_CREDENTIAL' }, () => { void chrome.runtime.lastError; });
    });
    document.documentElement.appendChild(saveHost);
  }

  function captureCredentials(target) {
    if (isInternal(target)) return;
    const root = target?.form || (target?.getRootNode?.() === document ? document : target?.getRootNode?.()) || document;
    const passwords = passwordFields(root);
    const password = passwords.find(isNewPassword) || passwords[0];
    const username = password ? findUsernameField(password) : inputs(root).find(field => isUsername(field) && isFieldVisible(field));
    if (username?.value.trim()) previousUsername = username.value.trim();
    if (!password?.value) return;
    const user = username?.value.trim() || previousUsername;
    const pass = password.value;
    const fingerprint = JSON.stringify([user, pass]);
    if (fingerprint === lastCapture || fingerprint === dismissedCapture) return;
    lastCapture = fingerprint;
    saveHost?.remove(); saveHost = null;
    chrome.runtime.sendMessage({ action: 'STAGE_CREDENTIAL', user, pass }, state => {
      if (!chrome.runtime.lastError && fingerprint === lastCapture) showSavePrompt(state);
    });
  }

  document.addEventListener('input', event => {
    const target = event.composedPath()[0];
    if (target?.tagName !== 'INPUT' || isInternal(target)) return;
    if (!isUsername(target) && target.type !== 'password' && !/password/.test(target.autocomplete)) return;
    // Invalidate the displayed consent while its underlying credentials are being edited.
    saveHost?.remove(); saveHost = null;
    lastCapture = '';
    if (isUsername(target) && target.value.trim()) {
      previousUsername = target.value.trim();
      chrome.runtime.sendMessage({ action: 'STAGE_USERNAME', user: previousUsername }, () => { void chrome.runtime.lastError; });
    }
    clearTimeout(captureTimer);
    captureTimer = setTimeout(() => captureCredentials(target), 700);
  }, true);
  document.addEventListener('change', event => {
    const target = event.composedPath()[0];
    if (target?.tagName === 'INPUT' && !isInternal(target)) captureCredentials(target);
  }, true);
  document.addEventListener('focusin', () => scanPage(), true);
  chrome.runtime.sendMessage({ action: 'GET_PENDING_CREDENTIAL' }, state => {
    if (!chrome.runtime.lastError) showSavePrompt(state);
  });

  // Immediately remove ALL badges when a form is submitted (login occurred)
  document.addEventListener('submit', event => {
    captureCredentials(event.target);
    clearAllBadges();
    setTimeout(cleanDeadBadges, 100);
    setTimeout(cleanDeadBadges, 400);
    setTimeout(cleanDeadBadges, 1000);
  }, true);

  // Catch login button clicks even when not inside a traditional form submit
  document.addEventListener('click', (e) => {
    const target = e.target;
    if (!target) return;
    const btn = target.closest('button, input[type="submit"], input[type="button"], [role="button"], a');
    if (btn) {
      const text = (btn.innerText || btn.value || btn.getAttribute('aria-label') || '').toLowerCase();
      if (/log\s*in|sign\s*(in|up)|register|create|دخول|تسجيل|إنشاء|submit|continue|متابعة/i.test(text)) {
        captureCredentials(btn);
        clearAllBadges();
        setTimeout(cleanDeadBadges, 100);
        setTimeout(cleanDeadBadges, 400);
        setTimeout(cleanDeadBadges, 1000);
      }
    }
  }, true);

  // Catch Enter key on input fields (which frequently triggers AJAX login in SPAs)
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target && (e.target.tagName === 'INPUT')) {
      captureCredentials(e.target);
      if (e.target.type === 'password' || e.target.type === 'email' || e.target.type === 'text') {
        setTimeout(cleanDeadBadges, 50);
        setTimeout(cleanDeadBadges, 300);
        setTimeout(cleanDeadBadges, 800);
      }
    }
  }, true);

  // Global click outside to dismiss dropdown
  document.addEventListener('mousedown', (e) => {
    if (activeDropdown && !activeDropdown.contains(e.target) && !e.target.closest('.reword-autofill-btn')) {
      closeDropdown();
    }
  });

  // Track SPA URL changes (pushState, replaceState, popstate, hashchange)
  let lastHref = window.location.href;
  function handleUrlChange() {
    if (window.location.href !== lastHref) {
      lastHref = window.location.href;
      clearAllBadges();
      setTimeout(scanPage, 200);
    }
  }

  window.addEventListener('popstate', () => { handleUrlChange(); cleanDeadBadges(); });
  window.addEventListener('hashchange', () => { handleUrlChange(); cleanDeadBadges(); });

  // Listen for 1-click autofill message from popup
  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.action === 'TRIGGER_AUTOFILL') {
      const passInput = passwordFields().find(field => !isNewPassword(field));
      const userInput = passInput ? findUsernameField(passInput) : inputs().find(field => isUsername(field) && isFieldVisible(field));
      
      let filled = false;
      if (userInput && msg.user) {
        setFieldValue(userInput, msg.user);
        filled = true;
      }
      if (passInput && msg.pass) {
        setFieldValue(passInput, msg.pass);
        filled = true;
      }
      sendResponse({ success: filled });
    }
    return true;
  });

  // Initial Scan
  scanPage();

  // Observer for dynamic SPAs (Single Page Applications)
  let scanTimer;
  const observer = new MutationObserver(records => {
    if (records.every(record => isInternal(record.target) ||
      (record.type === 'childList' && [...record.addedNodes, ...record.removedNodes].every(node =>
        node.nodeType === Node.ELEMENT_NODE && isInternal(node))))) return;
    clearTimeout(scanTimer);
    scanTimer = setTimeout(scanPage, 80);
  });

  observer.observe(document.body || document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style', 'class', 'hidden', 'type', 'aria-hidden', 'disabled']
  });

  // Periodic safety check to ensure dead badges never linger after login or SPA transitions
  setInterval(() => {
    handleUrlChange();
    scanPage();
  }, 350);
})();
