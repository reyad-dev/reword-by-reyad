// ReWord Chrome Extension - Background Service Worker (Manifest V3)
import { CV, cleanWorkerUrl, extractDomain } from './crypto.js';

let IN_MEMORY_VAULT = null;
let IN_MEMORY_MP = null;
let AUTO_LOCK_MINUTES = 30;
const CAPTURE_TTL = 10 * 60 * 1000;
let saveQueue = Promise.resolve();
const captureKey = sender => `capture:${sender.tab.id}:${sender.frameId || 0}`;
function pageOrigin(sender) {
  try {
    const url = new URL(sender.url);
    return /^https?:$/.test(url.protocol) ? url.origin : null;
  } catch { return null; }
}

chrome.tabs.onRemoved.addListener(async tabId => {
  const session = await chrome.storage.session.get(null);
  const keys = Object.keys(session).filter(key => key.startsWith(`capture:${tabId}:`));
  if (keys.length) await chrome.storage.session.remove(keys);
});

// Initialize alarms for auto-lock
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'reword-auto-lock') {
    lockVault();
  }
});

function resetAutoLock() {
  chrome.alarms.create('reword-auto-lock', { delayInMinutes: AUTO_LOCK_MINUTES });
}

// Restore session from chrome.storage.session if worker was terminated
async function getSession() {
  if (IN_MEMORY_VAULT && IN_MEMORY_MP) {
    resetAutoLock();
    return { vault: IN_MEMORY_VAULT, mp: IN_MEMORY_MP, isUnlocked: true };
  }
  try {
    const session = await chrome.storage.session.get(['mp', 'isUnlocked']);
    if (session?.isUnlocked && session.mp) {
      const local = await chrome.storage.local.get(['encVault']);
      if (local?.encVault) {
        const vault = await CV.dec(local.encVault, session.mp);
        IN_MEMORY_VAULT = vault;
        IN_MEMORY_MP = session.mp;
        resetAutoLock();
        return { vault, mp: session.mp, isUnlocked: true };
      }
    }
  } catch (err) {
    console.warn('Session restore failed:', err);
  }
  return { vault: null, mp: null, isUnlocked: false };
}

async function lockVault() {
  IN_MEMORY_VAULT = null;
  IN_MEMORY_MP = null;
  try {
    await chrome.storage.session.clear();
    await chrome.alarms.clear('reword-auto-lock');
  } catch {}
  notifyAllTabs('VAULT_LOCKED');
}

// Sync Pull from Cloudflare Worker
async function syncPull(sid, wurl) {
  if (!sid || !wurl) return null;
  try {
    const id = await CV.hashId(sid);
    const res = await fetch(`${wurl}/vault?id=${id}`);
    if (!res.ok) return null;
    return await res.json(); // { data, ts }
  } catch (err) {
    console.error('Sync Pull Error:', err);
    return null;
  }
}

// Sync Push to Cloudflare Worker
async function syncPush(encData, sid, wurl) {
  if (!sid || !wurl) return false;
  try {
    const id = await CV.hashId(sid);
    const ts = Date.now();
    const res = await fetch(`${wurl}/vault`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, data: encData, ts })
    });
    if (res.ok || res.status === 409) {
      await chrome.storage.local.set({ vaultTs: ts });
      return true;
    }
  } catch (err) {
    console.error('Sync Push Error:', err);
  }
  return false;
}

// Notify content scripts of state changes
function notifyAllTabs(action, data = {}) {
  chrome.tabs.query({}, (tabs) => {
    for (const tab of tabs) {
      if (tab.id) {
        chrome.tabs.sendMessage(tab.id, { action, ...data }).catch(() => {});
      }
    }
  });
}

// Message Listener for Popup and Content Scripts
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    let releaseSave;
    if (['SAVE_CREDENTIAL', 'CONFIRM_PENDING_CREDENTIAL'].includes(msg.action)) {
      const previous = saveQueue;
      saveQueue = new Promise(resolve => { releaseSave = resolve; });
      await previous;
    }
    try {
      switch (msg.action) {
        case 'STAGE_USERNAME':
        case 'STAGE_CREDENTIAL':
        case 'GET_PENDING_CREDENTIAL':
        case 'DISMISS_CREDENTIAL': {
          const origin = pageOrigin(sender);
          if (!sender.tab || !origin) { sendResponse({ pending: false }); return; }
          const key = captureKey(sender);
          const previous = (await chrome.storage.session.get(key))[key];
          if (msg.action === 'DISMISS_CREDENTIAL') {
            await chrome.storage.session.remove(key);
            sendResponse({ success: true });
            return;
          }
          if (msg.action === 'STAGE_CREDENTIAL') {
            if (typeof msg.pass !== 'string' || !msg.pass || typeof msg.user !== 'string') {
              sendResponse({ pending: false }); return;
            }
            // Session storage is restricted to trusted extension contexts and never synced to disk/cloud.
            await chrome.storage.session.set({ [key]: {
              origin, user: msg.user.trim() || (previous?.origin === origin && previous.expires > Date.now() ? previous.user : ''),
              pass: msg.pass, expires: Date.now() + CAPTURE_TTL
            } });
          }
          if (msg.action === 'STAGE_USERNAME') {
            if (typeof msg.user === 'string' && msg.user.trim()) {
              await chrome.storage.session.set({ [key]: {
                ...(previous?.origin === origin && previous.expires > Date.now() ? previous : {}),
                origin, user: msg.user.trim(), expires: Date.now() + CAPTURE_TTL
              } });
            }
            sendResponse({ pending: false }); return;
          }
          const pending = (await chrome.storage.session.get(key))[key];
          if (!pending?.pass || pending.origin !== origin || pending.expires < Date.now()) {
            if (pending?.expires < Date.now()) await chrome.storage.session.remove(key);
            sendResponse({ pending: false }); return;
          }
          const session = await getSession();
          const domain = extractDomain(origin);
          const account = session.vault?.sites?.filter(site => extractDomain(site.url) === domain)
            .flatMap(site => site.accounts || []).find(account => account.user === pending.user);
          sendResponse({ pending: account?.pass !== pending.pass, user: pending.user,
            domain, update: Boolean(account), isLocked: !session.isUnlocked });
          return;
        }
        case 'GET_STATUS': {
          const session = await getSession();
          const local = await chrome.storage.local.get(['encVault', 'syncId', 'workerUrl', 'meta']);
          const sites = session.vault?.sites || [];
          const accountsCount = sites.reduce((sum, s) => sum + (s.accounts?.length || 0), 0);
          sendResponse({
            isUnlocked: session.isUnlocked,
            hasVault: Boolean(local.encVault),
            syncId: local.syncId || null,
            workerUrl: local.workerUrl || null,
            hint: local.meta?.hint || '',
            stats: { sites: sites.length, accounts: accountsCount }
          });
          break;
        }

        case 'UNLOCK_VAULT': {
          const { password } = msg;
          const local = await chrome.storage.local.get(['encVault', 'syncId', 'workerUrl', 'vaultTs']);
          let enc = local.encVault;
          
          // Try sync from cloud before opening if credentials available
          if (local.syncId && local.workerUrl) {
            try {
              const cloud = await syncPull(local.syncId, local.workerUrl);
              if (cloud?.data) {
                // Verify with password
                await CV.dec(cloud.data, password);
                const localTs = local.vaultTs || 0;
                if ((cloud.ts || 0) >= localTs) {
                  enc = cloud.data;
                  await chrome.storage.local.set({ encVault: enc, vaultTs: cloud.ts });
                }
              }
            } catch (syncErr) {
              console.warn('Cloud sync on unlock failed, using local:', syncErr);
            }
          }

          if (!enc) {
            sendResponse({ success: false, error: 'no_vault' });
            return;
          }

          try {
            const vault = await CV.dec(enc, password);
            IN_MEMORY_VAULT = vault;
            IN_MEMORY_MP = password;
            await chrome.storage.session.set({ mp: password, isUnlocked: true });
            resetAutoLock();
            notifyAllTabs('VAULT_UNLOCKED');
            sendResponse({ success: true, vault });
          } catch (decErr) {
            sendResponse({ success: false, error: 'invalid_password' });
          }
          break;
        }

        case 'CONNECT_VAULT': {
          const { syncId, workerUrl, password } = msg;
          const cleanUrl = cleanWorkerUrl(workerUrl);
          if (!syncId || !cleanUrl || !password) {
            sendResponse({ success: false, error: 'missing_fields' });
            return;
          }

          const cloud = await syncPull(syncId, cleanUrl);
          if (!cloud || !cloud.data) {
            sendResponse({ success: false, error: 'vault_not_found' });
            return;
          }

          try {
            const vault = await CV.dec(cloud.data, password);
            IN_MEMORY_VAULT = vault;
            IN_MEMORY_MP = password;
            await chrome.storage.local.set({
              encVault: cloud.data,
              vaultTs: cloud.ts || Date.now(),
              syncId,
              workerUrl: cleanUrl,
              meta: vault.meta || {}
            });
            await chrome.storage.session.set({ mp: password, isUnlocked: true });
            resetAutoLock();
            notifyAllTabs('VAULT_UNLOCKED');
            sendResponse({ success: true, vault });
          } catch {
            sendResponse({ success: false, error: 'invalid_password' });
          }
          break;
        }

        case 'SETUP_NEW_VAULT': {
          const { password, hint, syncId, workerUrl } = msg;
          const cleanUrl = cleanWorkerUrl(workerUrl);
          const newVault = { meta: { hint: hint || '', v: 1 }, sites: [] };
          const enc = await CV.enc(newVault, password);
          
          await chrome.storage.local.set({
            encVault: enc,
            vaultTs: Date.now(),
            syncId: syncId || null,
            workerUrl: cleanUrl || null,
            meta: { hint: hint || '', v: 1 }
          });

          if (syncId && cleanUrl) {
            await syncPush(enc, syncId, cleanUrl);
          }

          IN_MEMORY_VAULT = newVault;
          IN_MEMORY_MP = password;
          await chrome.storage.session.set({ mp: password, isUnlocked: true });
          resetAutoLock();
          sendResponse({ success: true, vault: newVault });
          break;
        }

        case 'LOCK_VAULT': {
          await lockVault();
          sendResponse({ success: true });
          break;
        }

        case 'GET_ALL_SITES': {
          const session = await getSession();
          if (!session.isUnlocked || !session.vault) {
            sendResponse({ isLocked: true, sites: [] });
            return;
          }
          sendResponse({ isLocked: false, sites: session.vault.sites || [] });
          break;
        }

        case 'GET_MATCHING_CREDENTIALS': {
          const { url } = msg;
          const session = await getSession();
          if (!session.isUnlocked || !session.vault) {
            sendResponse({ isLocked: true, matches: [] });
            return;
          }

          const targetDomain = extractDomain(url);
          const matches = [];
          const allAccounts = [];

          for (const site of session.vault.sites || []) {
            const siteDomain = extractDomain(site.url || site.name);
            const matchesDomain =
              siteDomain && targetDomain &&
              (siteDomain === targetDomain ||
               targetDomain.endsWith('.' + siteDomain) ||
               siteDomain.endsWith('.' + targetDomain) ||
               site.name.toLowerCase().includes(targetDomain) ||
               targetDomain.includes(site.name.toLowerCase()));

            if (Array.isArray(site.accounts)) {
              for (const acc of site.accounts) {
                const item = {
                  siteId: site.id,
                  siteName: site.name,
                  siteUrl: site.url,
                  accountId: acc.id,
                  user: acc.user,
                  pass: acc.pass,
                  notes: acc.notes || ''
                };
                allAccounts.push(item);
                if (matchesDomain) {
                  matches.push(item);
                }
              }
            }
          }

          sendResponse({ isLocked: false, domain: targetDomain, matches, allAccounts });
          break;
        }

        case 'CONFIRM_PENDING_CREDENTIAL':
        case 'SAVE_CREDENTIAL': {
          let credential = msg;
          if (msg.action === 'CONFIRM_PENDING_CREDENTIAL') {
            const origin = pageOrigin(sender);
            if (!sender.tab || !origin) { sendResponse({ success: false, error: 'invalid_origin' }); return; }
            const pending = (await chrome.storage.session.get(captureKey(sender)))[captureKey(sender)];
            if (!pending || pending.origin !== origin || pending.expires < Date.now()) {
              sendResponse({ success: false, error: 'capture_expired' }); return;
            }
            credential = { ...pending, siteName: extractDomain(origin), siteUrl: origin };
          }
          let { siteName, siteUrl, user, pass, notes } = credential;
          if (sender.tab) {
            const origin = pageOrigin(sender);
            if (!origin) { sendResponse({ success: false, error: 'invalid_origin' }); return; }
            siteUrl = origin;
            siteName = extractDomain(origin);
          }
          if (typeof pass !== 'string' || !pass || typeof user !== 'string') {
            sendResponse({ success: false, error: 'missing_credentials' }); return;
          }
          const session = await getSession();
          if (!session.isUnlocked || !session.vault || !session.mp) {
            sendResponse({ success: false, error: 'vault_locked' });
            return;
          }

          const vault = structuredClone(session.vault);
          const domain = extractDomain(siteUrl || siteName);

          // Find or create site
          let site = vault.sites.find(s => {
            const d = extractDomain(s.url || s.name);
            return d === domain || s.name.toLowerCase() === siteName.toLowerCase();
          });

          if (!site) {
            site = {
              id: Date.now().toString() + Math.random().toString(36).slice(2),
              name: siteName || domain,
              url: siteUrl || `https://${domain}`,
              accounts: []
            };
            vault.sites.push(site);
          }

          // Check if account with same username exists
          let acc = site.accounts.find(a => a.user === user);
          if (acc) {
            acc.pass = pass;
            if (notes) acc.notes = notes;
            acc.updated = Date.now();
          } else {
            site.accounts.push({
              id: Date.now().toString() + Math.random().toString(36).slice(2),
              user,
              pass,
              notes: notes || '',
              created: Date.now()
            });
          }

          // Re-encrypt and save
          const enc = await CV.enc(vault, session.mp);
          const ts = Date.now();
          await chrome.storage.local.set({ encVault: enc, vaultTs: ts });

          const local = await chrome.storage.local.get(['syncId', 'workerUrl']);
          if (local.syncId && local.workerUrl) {
            syncPush(enc, local.syncId, local.workerUrl);
          }

          IN_MEMORY_VAULT = vault;
          if (sender.tab) await chrome.storage.session.remove(captureKey(sender));
          sendResponse({ success: true, siteId: site.id });
          break;
        }

        case 'SYNC_NOW': {
          const session = await getSession();
          const local = await chrome.storage.local.get(['syncId', 'workerUrl', 'vaultTs', 'encVault']);
          if (!local.syncId || !local.workerUrl) {
            sendResponse({ success: false, error: 'no_sync_settings' });
            return;
          }

          const cloud = await syncPull(local.syncId, local.workerUrl);
          let updated = false;

          if (cloud?.data && session.isUnlocked && session.mp) {
            try {
              const cloudVault = await CV.dec(cloud.data, session.mp);
              const localTs = local.vaultTs || 0;
              if ((cloud.ts || 0) > localTs) {
                IN_MEMORY_VAULT = cloudVault;
                await chrome.storage.local.set({ encVault: cloud.data, vaultTs: cloud.ts });
                updated = true;
              } else if (localTs > (cloud.ts || 0) && local.encVault) {
                await syncPush(local.encVault, local.syncId, local.workerUrl);
              }
            } catch (e) {
              console.warn('Sync decode error:', e);
            }
          }

          sendResponse({ success: true, updated });
          break;
        }

        default:
          sendResponse({ error: 'unknown_action' });
      }
    } catch (globalErr) {
      console.error('Background message error:', globalErr);
      sendResponse({ success: false, error: globalErr.message });
    } finally {
      releaseSave?.();
    }
  })();

  return true; // Keep message channel open for async response
});
