/**
 * Sixty lines, by hand, instead of Workbox.
 *
 * The whole job is: keep the shell so the app opens with no network, and never cache the sync
 * API so it is never answered with someone's stale ledger. That is two rules, and a build-time
 * generator to express them would be more moving parts than the rules.
 *
 * This matters more here than in most places. During a national disruption an installed PWA
 * still opens from this cache — but the first install and every update need the network, which
 * is exactly why the Android app is the household's sensor and this is the companion.
 */

// The name is the version, and bumping it is how the cache is emptied: the fetch handler below
// `cache.put`s every asset it ever serves — including hashed bundles whose names change on every
// deploy — and nothing else ever removes an entry, so without a bump the old builds pile up for
// ever. Bump it whenever the shell's shape changes; activate deletes every cache that is not
// this one.
const SHELL = 'muchtoman-shell-v3';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL).then((cache) => cache.addAll(['/', '/manifest.webmanifest'])),
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Never cached. A ledger answered from cache is a wrong answer about money, and `/rates`
  // already has its own edge cache with a sensible window.
  if (url.pathname.startsWith('/v1/') || url.pathname === '/rates') return;

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(SHELL).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(async () => {
        const cached = await caches.match(request);
        if (cached) return cached;
        // A navigation with nothing cached still gets the shell rather than a browser error.
        if (request.mode === 'navigate') {
          const shell = await caches.match('/');
          if (shell) return shell;
        }
        return new Response('آفلاین', { status: 503, headers: { 'content-type': 'text/plain; charset=utf-8' } });
      }),
  );
});

/**
 * Contentless on purpose, and not only for privacy: the Worker could not put a figure in one if
 * it wanted to, because everything it holds is ciphertext it has no key for.
 */
self.addEventListener('push', (event) => {
  event.waitUntil(
    self.registration.showNotification('چقدر تومن', {
      body: 'بررسی هفتگی‌ات آماده است.',
      tag: 'weekly-close',
      dir: 'rtl',
      lang: 'fa',
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(self.clients.openWindow('/'));
});
