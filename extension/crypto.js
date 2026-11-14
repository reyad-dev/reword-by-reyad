// ReWord Cryptographic Engine (Matches core.js 100%)
// Uses Web Crypto API: AES-256-GCM + PBKDF2 (310,000 iterations) + SHA-256

export const CV = {
  b64e(a) {
    let s = '';
    for (let i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
    return btoa(s);
  },

  b64d(s) {
    const b = atob(s);
    const a = new Uint8Array(b.length);
    for (let i = 0; i < b.length; i++) a[i] = b.charCodeAt(i);
    return a;
  },

  async key(pwd, salt) {
    const k = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(pwd),
      'PBKDF2',
      false,
      ['deriveKey']
    );
    return crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt, iterations: 310000, hash: 'SHA-256' },
      k,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt']
    );
  },

  async enc(data, pwd) {
    const s = crypto.getRandomValues(new Uint8Array(32));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const e = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      await this.key(pwd, s),
      new TextEncoder().encode(JSON.stringify(data))
    );
    const o = new Uint8Array(44 + e.byteLength);
    o.set(s);
    o.set(iv, 32);
    o.set(new Uint8Array(e), 44);
    return this.b64e(o);
  },

  async dec(b64, pwd) {
    const b = this.b64d(b64);
    const k = await this.key(pwd, b.slice(0, 32));
    const d = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: b.slice(32, 44) },
      k,
      b.slice(44)
    );
    return JSON.parse(new TextDecoder().decode(d));
  },

  async hashId(id) {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('reword:v1:' + id));
    return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
  }
};

export function cleanWorkerUrl(url) {
  if (!url) return '';
  let u = url.trim();
  u = u.replace(/['"`‘’“”′‘]+/g, '');
  u = u.replace(/\/+$/, '').replace(/\/vault$/, '');
  u = u.trim().replace(/^\/+/, '');
  if (u && !/^https?:\/\//i.test(u)) {
    u = 'https://' + u;
  }
  return u;
}

// Exact host, a subdomain of the saved host, or its parent domain. Sibling subdomains and
// substring/brand-name matches are deliberately rejected (x.com must not match netflix.com,
// alice.github.io must not match bob.github.io, "Facebook" must not match facebook.xyz).
const SHARED_SUFFIXES = ['github.io', 'gitlab.io', 'herokuapp.com', 'blogspot.com', 'netlify.app', 'vercel.app',
  'pages.dev', 'workers.dev', 'web.app', 'firebaseapp.com', 'appspot.com', 'azurewebsites.net', 'wordpress.com'];

export function hostMatches(pageHost, savedHost) {
  if (!pageHost || !savedHost || !pageHost.includes('.') || !savedHost.includes('.')) return false;
  if (pageHost === savedHost || pageHost.endsWith('.' + savedHost)) return true;
  return savedHost.endsWith('.' + pageHost) && !SHARED_SUFFIXES.includes(pageHost);
}

// A site's url field may hold several URLs; a site without URL may be named like a domain.
export function siteMatchesDomain(site, pageHost) {
  if (!site || !pageHost) return false;
  const urls = String(site.url || '').split(/[,;\s]+/).map(u => extractDomain(u)).filter(h => h.includes('.'));
  if (urls.length) return urls.some(h => hostMatches(pageHost, h));
  const name = String(site.name || '').trim().toLowerCase();
  return name.includes('.') && !name.includes(' ') && hostMatches(pageHost, extractDomain(name));
}

export const sameUser = (a, b) => String(a || '').trim().toLowerCase() === String(b || '').trim().toLowerCase();

export function accountMatchesCredential(account, { user, email }) {
  const aliases = [account.user, account.email].filter(value => String(value || '').trim());
  const identifiers = [user, email].filter(value => String(value || '').trim());
  if (!identifiers.length) return false;
  if (user && email && account.user && !sameUser(account.user, user) && !sameUser(account.user, email)) return false;
  if (email && account.email && !sameUser(account.email, email)) return false;
  return identifiers.some(value => aliases.some(alias => sameUser(alias, value)));
}

export function accountContainsCredential(account, credential) {
  if (account.pass !== credential.pass) return false;
  if (!credential.user && !credential.email) return true;
  const aliases = [account.user, account.email].filter(Boolean);
  return [credential.user, credential.email].filter(Boolean).every(value => aliases.some(alias => sameUser(alias, value)));
}

export function extractDomain(url) {
  if (!url) return '';
  try {
    let clean = url.trim();
    if (!/^https?:\/\//i.test(clean)) clean = 'https://' + clean;
    const parsed = new URL(clean);
    let host = parsed.hostname.toLowerCase();
    // إزالة www.
    if (host.startsWith('www.')) host = host.slice(4);
    return host;
  } catch {
    return url.toLowerCase().replace(/^https?:\/\//i, '').replace(/^www\./i, '').split('/')[0].split('?')[0];
  }
}
