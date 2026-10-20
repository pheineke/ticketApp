"""
Remote "Android WebView" login.

Runs a real Chromium (Playwright) on the container's virtual display, emulating
the myVRN app's WebView: phone viewport + touch, a WebView User-Agent (`; wv`)
and the `X-Requested-With` header Android WebViews send. The VRN login SPA only
allows login on mobile devices, and the server only answers browser-like TLS
clients — a real Chromium satisfies both.

The user drives the browser through noVNC. When the IdentityServer redirects to
the app's custom scheme (`vrn://oauth/callback?code=...`), we read the code
from the redirect's Location header and hand it to a callback — no copy/paste.
"""
from __future__ import annotations

import os
import threading
import time
import urllib.parse

from playwright.sync_api import sync_playwright

WEBVIEW_UA = os.environ.get(
    "VRN_WEBVIEW_UA",
    "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 "
    "Mobile Safari/537.36",
)
APP_PACKAGE = os.environ.get("VRN_APP_PACKAGE", "com.mdv.VRNCompanion")
LOGIN_TIMEOUT = int(os.environ.get("VRN_REMOTE_LOGIN_TIMEOUT", "900"))  # seconds


class RemoteLogin:
    """One browser at a time (single-user dev tool)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self.status = {"state": "idle", "detail": "", "updated": time.time()}

    def _set(self, state: str, detail: str = ""):
        self.status = {"state": state, "detail": detail, "updated": time.time()}

    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    def start(self, authorize_url: str, redirect_uri: str, on_callback):
        """Open `authorize_url`; call `on_callback(code, state)` on redirect."""
        with self._lock:
            self.stop()
            self._stop = threading.Event()
            self._thread = threading.Thread(
                target=self._run, args=(authorize_url, redirect_uri, on_callback, self._stop),
                daemon=True,
            )
            self._set("starting")
            self._thread.start()

    def stop(self):
        if self.running():
            self._stop.set()
            self._thread.join(timeout=15)

    def _run(self, authorize_url, redirect_uri, on_callback, stop):
        captured: dict = {}

        def check(url: str | None):
            if url and url.startswith(redirect_uri) and "code" not in captured:
                q = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
                captured["code"] = (q.get("code") or [None])[0]
                captured["state"] = (q.get("state") or [None])[0]
                captured["error"] = (q.get("error") or [None])[0]

        try:
            with sync_playwright() as p:
                browser = p.chromium.launch(
                    headless=False,
                    # Kiosk: no browser chrome, window fills the 412x860 display
                    # so the noVNC view is exactly the emulated phone screen.
                    args=["--no-sandbox", "--disable-dev-shm-usage", "--kiosk",
                          "--window-position=0,0", "--window-size=412,860",
                          "--disable-infobars", "--no-first-run"],
                )
                ctx = browser.new_context(
                    user_agent=WEBVIEW_UA,
                    viewport={"width": 412, "height": 770},  # display minus the tab/address bar,
                    device_scale_factor=2.625,
                    is_mobile=True,
                    has_touch=True,
                    locale="de-DE",
                    timezone_id="Europe/Berlin",
                    extra_http_headers={"X-Requested-With": APP_PACKAGE},
                )
                page = ctx.new_page()
                # The custom-scheme hop shows up as a 302 whose Location is vrn://...
                ctx.on("response", lambda r: check(r.headers.get("location")))
                ctx.on("request", lambda r: check(r.url))
                self._set("waiting", "Sign in inside the remote browser.")
                try:
                    page.goto(authorize_url, wait_until="domcontentloaded", timeout=60_000)
                except Exception:
                    pass  # an immediate vrn:// redirect aborts the navigation; that's fine
                deadline = time.time() + LOGIN_TIMEOUT
                while "code" not in captured and not stop.is_set() and time.time() < deadline:
                    try:
                        page.wait_for_timeout(500)
                    except Exception:
                        break  # page/browser closed
                browser.close()
        except Exception as e:
            self._set("error", f"Browser failed: {e}")
            return

        if captured.get("error"):
            self._set("error", f"Authorization error: {captured['error']}")
        elif captured.get("code"):
            self._set("exchanging")
            err = on_callback(captured["code"], captured.get("state"))
            self._set("error", err) if err else self._set("done", "Tokens received.")
        elif stop.is_set():
            self._set("idle", "Cancelled.")
        else:
            self._set("error", "Timed out waiting for the login to finish.")


remote = RemoteLogin()
