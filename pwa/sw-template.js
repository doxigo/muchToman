const SHELL = '__SHELL_VERSION__';
const PRECACHE = __PRECACHE__;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((cache) => cache.addAll(PRECACHE)));
});
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith('muchtoman-shell-') && k !== SHELL).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/v1/') || url.pathname === '/rates') return;
  if (request.mode !== 'navigate' && !PRECACHE.includes(url.pathname)) return;
  event.respondWith(caches.open(SHELL).then(async (cache) => {
    const key = request.mode === 'navigate' ? '/' : url.pathname;
    const cached = await cache.match(key);
    if (cached) return cached;
    const response = await fetch(key);
    if (response.ok) await cache.put(key, response.clone());
    return response;
  }));
});
self.addEventListener('push', (event) => {
  event.waitUntil(self.registration.showNotification('چقدر تومن', {
    body: 'بررسی هفتگی‌ات آماده است.', tag: 'weekly-close', dir: 'rtl', lang: 'fa',
  }));
});
// A note's tap lands on the tab it is about (Notify.kt's open-tab extra): an open window is
// focused and told where to go; otherwise the app opens there.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const tab = event.notification.data?.tab;
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
    const open = windows[0];
    if (open) { if (tab) open.postMessage({ type: 'open-tab', tab }); return open.focus(); }
    return self.clients.openWindow(tab ? `/?tab=${encodeURIComponent(tab)}` : '/');
  }));
});
