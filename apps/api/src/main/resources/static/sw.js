// Only public app files are cached. Private photos, sessions and API responses never are.
const CACHE='little-garden-shell-v2';
const SHELL=['/','/style.css','/app.js','/garden-utils.mjs','/manifest.webmanifest','/icon-192.png','/icon-512.png'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL))));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('little-garden-shell-')&&key!==CACHE).map(key=>caches.delete(key))))));
self.addEventListener('fetch',event=>{
 const url=new URL(event.request.url);
 if(event.request.method!=='GET'||url.origin!==self.location.origin||url.search||!SHELL.includes(url.pathname))return;
 event.respondWith(fetch(event.request).catch(()=>caches.match(event.request)));
});
