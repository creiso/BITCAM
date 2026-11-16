/* Cache do app para abrir offline; a rede atualiza o cache em segundo plano. */
var CACHE = 'bitcam-v1';
var FILES = ['./', 'index.html', 'style.css', 'app.js', 'worker.js', 'pipeline.js', 'icon.svg', 'icon-192.png', 'icon-512.png', 'manifest.webmanifest'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(FILES); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) {
    return Promise.all(ks.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(caches.open(CACHE).then(function (c) {
    return c.match(e.request).then(function (hit) {
      var net = fetch(e.request).then(function (r) { if (r.ok) c.put(e.request, r.clone()); return r; });
      return hit ? (net.catch(function () {}), hit) : net;
    });
  }));
});
