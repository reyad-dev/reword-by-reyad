const CACHE_NAME = 'reword-v13';
const ASSETS = [
  './index.html',
  './style.css?v=14',
  './core.js?v=4',
  './ui.js?v=9',
  './manifest.json',
  './repo-icon.svg?v=2'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  // لا تعترض الطلبات الخارجية (مثل الـ Worker API) أو الطلبات التي ليست GET
  if (e.request.method !== 'GET' || !e.request.url.startsWith(self.location.origin)) {
    return;
  }

  // استراتيجية Network-First مع حفظ الكاش: جلب أحدث نسخة دائماً عند وجود إنترنت، والرجوع للكاش عند انقطاع الاتصال
  e.respondWith(
    fetch(e.request).then(response => {
      if (response && response.status === 200 && response.type === 'basic') {
        const responseClone = response.clone();
        caches.open(CACHE_NAME).then(cache => {
          cache.put(e.request, responseClone);
        });
      }
      return response;
    }).catch(() => {
      // عند انقطاع الإنترنت، يتم الاسترجاع من الكاش
      return caches.match(e.request).then(cached => {
        if (cached) return cached;
        if (e.request.mode === 'navigate' || e.request.headers.get('accept')?.includes('text/html')) {
          return caches.match('./index.html');
        }
      });
    })
  );
});
