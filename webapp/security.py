"""
Hardening for running the app on a public domain.

- PUBLIC_MODE (default on): disables the tools that are only safe on a trusted LAN —
  the free-form request probe and OIDC config editor (both let a visitor make the
  server fetch arbitrary URLs, i.e. SSRF) and the remote WebView (one Chromium shared
  by everyone, viewed through noVNC).
- ACCESS_PASSWORD (optional): a gate in front of the whole app, for running it
  for yourself or a few people only.
- CSRF tokens on every POST, login rate limiting, security headers.
"""
from __future__ import annotations

import hmac
import os
import secrets
import threading
import time
from collections import defaultdict, deque

from flask import abort, request


def env_flag(name: str, default: bool) -> bool:
    v = os.environ.get(name)
    return default if v is None else v.strip().lower() in ("1", "true", "yes", "on")


PUBLIC_MODE = env_flag("PUBLIC_MODE", True)
ACCESS_PASSWORD = os.environ.get("ACCESS_PASSWORD", "")
# Behind a TLS-terminating proxy (Caddy, nginx, Cloudflare Tunnel) cookies must be Secure.
COOKIE_SECURE = env_flag("COOKIE_SECURE", PUBLIC_MODE)
# Number of reverse proxies in front of the app whose X-Forwarded-* headers to trust.
TRUSTED_PROXIES = int(os.environ.get("TRUSTED_PROXIES", "1" if PUBLIC_MODE else "0"))

SESSION_IDLE_TTL = int(os.environ.get("SESSION_IDLE_TTL", str(12 * 3600)))   # seconds
SESSION_MAX_AGE = int(os.environ.get("SESSION_MAX_AGE", str(7 * 86400)))
MAX_SESSIONS = int(os.environ.get("MAX_SESSIONS", "500"))


# --------------------------------------------------------------------------
# CSRF
# --------------------------------------------------------------------------
def csrf_check(expected: str | None):
    """Abort with 400 unless the POST carries the session's CSRF token."""
    sent = request.form.get("_csrf") or request.headers.get("X-CSRF-Token") or ""
    if not expected or not hmac.compare_digest(sent, expected):
        abort(400, "CSRF token missing or invalid. Reload the page and try again.")


def new_csrf() -> str:
    return secrets.token_urlsafe(24)


# --------------------------------------------------------------------------
# Rate limiting (in memory, single worker)
# --------------------------------------------------------------------------
class RateLimiter:
    """Sliding window: at most `limit` events per `window` seconds per key."""

    def __init__(self, limit: int, window: int):
        self.limit, self.window = limit, window
        self._hits: dict[str, deque] = defaultdict(deque)
        self._lock = threading.Lock()

    def hit(self, key: str) -> bool:
        """Record an attempt; False if the key is over its limit."""
        now = time.time()
        with self._lock:
            q = self._hits[key]
            while q and q[0] <= now - self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            if len(self._hits) > 10000:  # bound memory
                for k in [k for k, v in self._hits.items() if not v][:5000]:
                    del self._hits[k]
            return True


login_by_ip = RateLimiter(limit=10, window=15 * 60)
login_by_account = RateLimiter(limit=5, window=15 * 60)
gate_by_ip = RateLimiter(limit=10, window=15 * 60)


def client_ip() -> str:
    return request.remote_addr or "?"


# --------------------------------------------------------------------------
# Access gate
# --------------------------------------------------------------------------
def gate_ok(password: str) -> bool:
    return bool(ACCESS_PASSWORD) and hmac.compare_digest(password.encode(), ACCESS_PASSWORD.encode())


# --------------------------------------------------------------------------
# Security headers
# --------------------------------------------------------------------------
CSP = "; ".join([
    "default-src 'self'",
    "script-src 'self' 'unsafe-inline'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "connect-src 'self'",
    "frame-src " + ("'none'" if PUBLIC_MODE else "*"),
    "frame-ancestors 'none'",
    "form-action 'self'",
    "base-uri 'none'",
    "object-src 'none'",
])


def add_headers(resp):
    resp.headers.setdefault("Content-Security-Policy", CSP)
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("X-Frame-Options", "DENY")
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    resp.headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    if COOKIE_SECURE:
        resp.headers.setdefault("Strict-Transport-Security", "max-age=31536000")
    if resp.mimetype == "text/html":
        # Pages contain personal data and tokens: never cache them.
        resp.headers["Cache-Control"] = "no-store"
    return resp
