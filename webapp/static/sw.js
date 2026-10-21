/* myVRN Lite service worker.
 * - Caches only the app shell (CSS, scripts, icons, the /offline page) — never the
 *   server-rendered pages, which contain personal data and tokens.
 * - Navigations go to the network; if that fails (no signal), /offline is shown, which
 *   renders the tickets from the browser's own offline copy (localStorage).
 */
const VERSION = "v18";
// Show the offline page if a page hasn't loaded within this time (slow/absent mobile data).
const NAV_TIMEOUT_MS = 2500;
const SHELL = `shell-${VERSION}`;
const ASSETS = [
  "/offline",
  "/static/style.css",
  "/static/ticket.js",
  "/static/vendor/bwip-js-min.js",
  "/static/icons/menu.svg",
  "/static/icons/ticket.svg",
  "/static/icons/settings.svg",
  "/static/icons/home_navigator_mobility_account_page.svg",
  "/static/icons/deutschlandticket.svg",
  "/static/icons/deutschlandticket_darkmode.svg",
  "/static/icons/vrn_logo.svg",
  "/static/icons/vrn_logo_dark.svg",
  "/static/icons/icon-192.png",
  "/manifest.webmanifest",
  "/static/icons/home_navigator_bulletin_board_page.svg",
  "/static/icons/home_navigator_help_and_contact_page.svg",
  "/static/icons/home_navigator_imprint_page.svg",
  "/static/icons/home_navigator_privacy_page.svg",
  "/static/icons/home_navigator_settings_page.svg",
  "/static/icons/qr_code.svg",
  "/static/icons/app_download.svg",
  "/static/icons/route.svg",
  "/static/icons/mode_bus.svg",
  "/static/icons/mode_tram.svg",
  "/static/icons/mode_train.svg",
  "/static/icons/mode_walk.svg",
  "/static/icons/warning.svg",
  "/static/icons/chevron_up.svg",
  "/static/icons/chevron_down.svg",
  "/static/icons/chevron_right.svg",
  "/static/icons/locate.svg",
  "/static/icons/vrn/vrn_logo.svg",
  "/static/icons/vrn/icon-192.png",
  "/static/icons/vrn/home_navigator_bulletin_board_page.svg",
  "/static/icons/vrn/home_navigator_help_and_contact_page.svg",
  "/static/icons/vrn/home_navigator_imprint_page.svg",
  "/static/icons/vrn/home_navigator_mobility_account_page.svg",
  "/static/icons/vrn/home_navigator_privacy_page.svg",
  "/static/icons/vrn/home_navigator_settings_page.svg"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(SHELL)
      .then((c) => c.addAll(ASSETS.map((u) => new Request(u, { credentials: "same-origin", cache: "reload" }))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  // APK downloads: straight to the network (large, and must not hit the offline timeout).
  if (url.pathname.startsWith("/app/download/")) return;

  // Pages: always from the network (personal data, never cached). No connection, or no
  // answer within NAV_TIMEOUT_MS -> /offline right away instead of waiting for the
  // browser's own (much longer) network timeout.
  if (req.mode === "navigate") {
    event.respondWith(navigate(req));
    return;
  }

  // Shell assets: serve from cache, refresh in the background (stale-while-revalidate).
  if (url.pathname.startsWith("/static/") || url.pathname === "/manifest.webmanifest") {
    event.respondWith(
      caches.open(SHELL).then((cache) =>
        cache.match(req).then((hit) => {
          const update = fetch(req).then((resp) => {
            if (resp.ok) cache.put(req, resp.clone());
            return resp;
          }).catch(() => hit);
          return hit || update;
        })
      )
    );
  }
});

function navigate(req) {
  const offlinePage = () => caches.match("/offline").then((r) => r || Response.error());
  if (self.navigator && self.navigator.onLine === false) return offlinePage();
  return new Promise((resolve) => {
    let settled = false;
    const done = (p) => { if (!settled) { settled = true; resolve(p); } };
    const timer = setTimeout(() => done(offlinePage()), NAV_TIMEOUT_MS);
    fetch(req).then((resp) => { clearTimeout(timer); done(resp); })
      .catch(() => { clearTimeout(timer); done(offlinePage()); });
  });
}
