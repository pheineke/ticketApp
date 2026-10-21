"""
myVRN Lite — web client in the original myVRN design.

Three areas, like the app's bottom tab bar:
  Tickets   /tickets, /tickets/<id>   my tickets + QR code (Mentz TicketShop API)
  Konto     /account                  login (browserless, see vrn_client.py) and profile
  Fahrplan   /fahrplan                connection search + route map (VRN EFA API, efa.py)
  Einstellungen /settings             theme + debug mode
  App        /app                     download of the native Android app (APK from downloads/)

Debug mode (Settings) adds the developer tools to each view: raw API responses,
token details, the alternative login paths (remote WebView via noVNC, manual
callback paste), the OIDC config and a free-form request probe.

This is a local dev tool. It handles real credentials/tokens — run it on your
own machine/LAN, not on a public host.
"""
from __future__ import annotations

import base64
import hashlib
import json
import sys
import os
import re
import secrets
import stat
import time
import urllib.parse
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from curl_cffi import requests
from flask import (
    Flask, abort, g, jsonify, make_response, redirect, render_template, request, send_from_directory,
    url_for,
)

import efa
import remember
import shop
import security as sec
import vrn_client
from remote_browser import remote
from werkzeug.middleware.proxy_fix import ProxyFix
import threading

# --------------------------------------------------------------------------
# Configuration (all overridable via environment)
# --------------------------------------------------------------------------
DEFAULT_CONFIG = {
    "base":           os.environ.get("VRN_BASE", "https://shop.myvrn.de"),
    "authorize_path": os.environ.get("VRN_AUTHORIZE_PATH", "/Identity/connect/authorize"),
    "token_path":     os.environ.get("VRN_TOKEN_PATH", "/Identity/connect/token"),
    "discovery_path": os.environ.get("VRN_DISCOVERY_PATH", "/Identity/.well-known/openid-configuration"),
    "client_id":      os.environ.get("VRN_CLIENT_ID", "vrn"),
    "redirect_uri":   os.environ.get("VRN_REDIRECT_URI", "vrn://oauth/callback"),
    "scope":          os.environ.get("VRN_SCOPE", "ticketing offline_access"),
    # Mentz extras the app appends to the authorize request (the server rewrites
    # appType to C5 anyway; kept for parity with the APK).
    "organization":   os.environ.get("VRN_ORGANIZATION", "vrn"),
    "client_usage":   os.environ.get("VRN_CLIENT_USAGE", "App"),
    "app_type":       os.environ.get("VRN_APP_TYPE", "Gullivr"),
    "prompt":         os.environ.get("VRN_PROMPT", ""),
    "ui_locales":     os.environ.get("VRN_UI_LOCALES", ""),
}

HTTP_TIMEOUT = int(os.environ.get("VRN_HTTP_TIMEOUT", "25"))

# shop.myvrn.de stalls non-browser TLS clients (python-requests, curl) for ~20s
# and then drops the connection. Impersonating Chrome's TLS/HTTP2 fingerprint
# gets an immediate response, so every outbound call impersonates Chrome.
IMPERSONATE = os.environ.get("VRN_IMPERSONATE", "chrome")

SHOP_BASE = os.environ.get("VRN_SHOP_BASE", "https://shop.myvrn.de/TicketShop/")
PROFILE_URL = ("https://shop.myvrn.de/Identity/OData/UserProfile"
               "?$expand=User($select=Name),User($expand=UserProperty)")
PAYMENT_INFO_URL = os.environ.get("VRN_PAYMENT_INFO_URL", "https://shop.myvrn.de/Payment/Customer/PaymentInfos")
TZ = ZoneInfo(os.environ.get("TZ", "Europe/Berlin"))
VNC_PORT = os.environ.get("VNC_PUBLIC_PORT", "8784")


class http:
    """Thin wrappers so every call carries the impersonation (no shared state)."""
    @staticmethod
    def get(url, **kw):
        return requests.get(url, impersonate=IMPERSONATE, **kw)

    @staticmethod
    def post(url, **kw):
        return requests.post(url, impersonate=IMPERSONATE, **kw)

    @staticmethod
    def request(method, url, **kw):
        return requests.request(method, url, impersonate=IMPERSONATE, **kw)


app = Flask(__name__)
app.secret_key = os.environ.get("FLASK_SECRET", secrets.token_hex(32))
if sec.TRUSTED_PROXIES:
    # Real client IP / scheme from the reverse proxy (rate limiting, Secure cookies).
    app.wsgi_app = ProxyFix(app.wsgi_app, x_for=sec.TRUSTED_PROXIES, x_proto=sec.TRUSTED_PROXIES,
                            x_host=sec.TRUSTED_PROXIES)


# --------------------------------------------------------------------------
# In-memory per-session store (fine for a single-user dev tool)
# --------------------------------------------------------------------------
@dataclass
class Session:
    config: dict = field(default_factory=lambda: dict(DEFAULT_CONFIG))
    pending: dict = field(default_factory=dict)      # state -> {verifier, created, authorize_url}
    tokens: dict | None = None                       # last token response
    token_received_at: float = 0.0
    flow: object | None = None                       # pending vrn_client.LoginFlow (2FA step)
    orders: dict | None = None                       # last ListOrdersV2 call record
    qr: dict = field(default_factory=dict)           # ticket id -> last QRCode call record
    profile: dict | None = None                      # last UserProfile call record
    discovery: dict | None = None
    probe: dict | None = None                        # last free-form probe
    trips: dict | None = None                        # last connection search (Fahrplan)
    shop_unis: list | None = None                    # Dfn/GetAuthorities (Hochschul-Login)
    shop_result: dict | None = None                  # result of the last ticket order
    invoices: dict = field(default_factory=dict)     # order id -> invoice PDF (Pdfconverter/Invoice)
    invoice_rec: dict | None = None                  # the last invoice request (debug, error)
    csrf: str = field(default_factory=sec.new_csrf)  # per-session CSRF token
    gate_ok: bool = False                            # passed the ACCESS_PASSWORD gate
    device: str | None = None                        # persistent-login cookie value (remember.py)
    device_clear: bool = False                       # tell the browser to drop an invalid device cookie
    remember: bool = True                            # "Angemeldet bleiben" for the next login
    created: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)


SESSIONS: dict[str, Session] = {}
SESSIONS_LOCK = threading.Lock()


def _expired(s: Session, now: float) -> bool:
    return now - s.last_seen > sec.SESSION_IDLE_TTL or now - s.created > sec.SESSION_MAX_AGE


def get_session() -> tuple[Session, str]:
    """The caller's own session (keyed by an unguessable cookie); created on demand."""
    if "sess" in g:  # one session per request, even before the cookie exists
        return g.sess, g.sid
    now = time.time()
    sid = request.cookies.get("sid")
    with SESSIONS_LOCK:
        for k in [k for k, v in SESSIONS.items() if _expired(v, now)]:
            del SESSIONS[k]
        sess = SESSIONS.get(sid) if sid else None
        fresh = sess is None
        if fresh:
            if len(SESSIONS) >= sec.MAX_SESSIONS:  # evict the least recently used
                del SESSIONS[min(SESSIONS, key=lambda k: SESSIONS[k].last_seen)]
            sid, sess = secrets.token_urlsafe(32), Session()
            SESSIONS[sid] = sess
        sess.last_seen = now
    g.sess, g.sid = sess, sid
    if fresh:
        restore_device(sess)
    return sess, sid


def restore_device(sess: Session):
    """Persistent login: re-establish gate access and VRN tokens from the device cookie."""
    cookie = request.cookies.get(remember.COOKIE)
    if not cookie:
        return
    rec = remember.restore(cookie)
    if rec is None:  # unknown, expired, or the access password changed
        sess.device_clear = True
        return
    sess.device, sess.gate_ok = cookie, True
    if rec["refresh_token"]:
        sess.tokens = {"refresh_token": rec["refresh_token"]}
        if refresh_tokens(sess):  # refresh token revoked/expired -> needs a real login again
            sess.tokens = None
            remember.update_token(cookie, None)


def remember_device(sess: Session, with_token: bool):
    """Create/refresh this browser's persistent login record."""
    rt = (sess.tokens or {}).get("refresh_token") if with_token else None
    if sess.device and remember.update_token(sess.device, rt):
        return
    sess.device, sess.device_clear = remember.create(rt), False


def rotate_session(sid: str) -> str:
    """New session id after login (prevents session fixation); same session object."""
    new = secrets.token_urlsafe(32)
    with SESSIONS_LOCK:
        sess = SESSIONS.pop(sid, None)
        if sess is not None:
            sess.csrf = sec.new_csrf()
            SESSIONS[new] = sess
    g.sid = new
    return new


def attach_cookie(resp, sid):
    resp.set_cookie("sid", sid, httponly=True, samesite="Lax", secure=sec.COOKIE_SECURE,
                    max_age=sec.SESSION_MAX_AGE)
    sess = g.get("sess")
    if sess is not None:
        if sess.device:
            resp.set_cookie(remember.COOKIE, sess.device, httponly=True, samesite="Lax",
                            secure=sec.COOKIE_SECURE, max_age=remember.MAX_AGE)
        elif sess.device_clear:
            resp.delete_cookie(remember.COOKIE, samesite="Lax", secure=sec.COOKIE_SECURE)
    return resp


# --------------------------------------------------------------------------
# Request guards: access gate, CSRF, public-mode route blocking, headers
# --------------------------------------------------------------------------
OPEN_ENDPOINTS = {"static", "healthz", "gate", "manifest", "service_worker", "offline"}
# Only safe on a trusted LAN: arbitrary outbound requests (SSRF) and a shared browser.
LAN_ONLY_ENDPOINTS = {"probe", "config", "discover", "login_remote", "login_remote_view",
                      "login_remote_status", "login_remote_stop"}


@app.before_request
def guards():
    if request.endpoint in ("static", "healthz", "manifest", "service_worker", "offline"):
        return None
    if sec.PUBLIC_MODE and request.endpoint in LAN_ONLY_ENDPOINTS:
        abort(404)
    sess, sid = get_session()
    if sec.ACCESS_PASSWORD and not sess.gate_ok and request.endpoint not in OPEN_ENDPOINTS:
        return attach_cookie(make_response(redirect(url_for("gate", next=request.path))), sid)
    if request.method == "POST":
        sec.csrf_check(sess.csrf)
    return None


@app.after_request
def headers(resp):
    return sec.add_headers(resp)


@app.route("/gate", methods=["GET", "POST"])
def gate():
    sess, sid = get_session()
    if not sec.ACCESS_PASSWORD or sess.gate_ok:
        return go(sid, "tickets")
    error = None
    if request.method == "POST":
        if not sec.gate_by_ip.hit(sec.client_ip()):
            error = "Zu viele Versuche. Bitte später erneut versuchen."
        elif sec.gate_ok(request.form.get("password", "")):
            sess.gate_ok = True
            if request.form.get("remember") == "1":
                remember_device(sess, with_token=False)
            sid = rotate_session(sid)
            nxt = request.args.get("next", "")
            return attach_cookie(make_response(redirect(nxt if nxt.startswith("/") and not nxt.startswith("//") else url_for("tickets"))), sid)
        else:
            error = "Falsches Passwort."
    return page(sess, sid, "gate.html", title=None, error=error, active="", hide_nav=True)


def page(sess: Session, sid: str, template: str, **kw):
    resp = make_response(render_template(template, cfg=sess.config, meta=token_meta(sess), **kw))
    return attach_cookie(resp, sid)


def go(sid: str, endpoint: str, **kw):
    return attach_cookie(make_response(redirect(url_for(endpoint, **kw))), sid)


# --------------------------------------------------------------------------
# Settings (per browser, in cookies so they survive container restarts)
# --------------------------------------------------------------------------
THEMES = ("system", "light", "dark")
# Colour scheme: "lite" = the red myVRN Lite palette (default), "vrn" = original VRN blue/navy.
PALETTES = ("lite", "vrn")
# Icons recoloured for the red palette; the original blue versions live in static/icons/vrn/.
RECOLOURED_ICONS = {"vrn_logo.svg", "icon-192.png", "home_navigator_bulletin_board_page.svg",
                    "home_navigator_help_and_contact_page.svg", "home_navigator_imprint_page.svg",
                    "home_navigator_mobility_account_page.svg", "home_navigator_privacy_page.svg",
                    "home_navigator_settings_page.svg"}


@app.context_processor
def inject_settings():
    theme = request.cookies.get("theme", "system")
    sess = g.get("sess")
    palette = request.cookies.get("palette", "lite")
    palette = palette if palette in PALETTES else "lite"

    def icon(name: str) -> str:
        """URL of an icon in the active colour scheme."""
        if palette == "vrn" and name in RECOLOURED_ICONS:
            return url_for("static", filename="icons/vrn/" + name)
        return url_for("static", filename="icons/" + name)

    return {
        "palette": palette,
        "icon": icon,
        "theme": theme if theme in THEMES else "system",
        "debug": request.cookies.get("debug") == "1",
        "public_mode": sec.PUBLIC_MODE,
        "offline_on": offline_enabled(),
        "csrf_token": sess.csrf if sess else "",
    }


# --------------------------------------------------------------------------
# Formatting helpers
# --------------------------------------------------------------------------
_NET_DATE = re.compile(r"/Date\((-?\d+)([+-]\d{4})?\)/")
_ISO = re.compile(r"(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}(?::\d{2})?)(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$")
_DE = re.compile(r"(\d{1,2})\.(\d{1,2})\.(\d{4})(?:,?\s+(\d{1,2}):(\d{2}))?$")


def parse_dt(value) -> datetime | None:
    """Parse the date shapes a .NET backend emits into an aware local datetime.

    Handles ISO 8601 with any number of fraction digits (.NET uses 7, which
    Python 3.10's fromisoformat rejects), "/Date(ms+hhmm)/" and dd.mm.yyyy.
    """
    if not value or not isinstance(value, str):
        return None
    v = value.strip()
    if m := _NET_DATE.fullmatch(v):
        return datetime.fromtimestamp(int(m.group(1)) / 1000, tz=timezone.utc).astimezone(TZ)
    if m := _ISO.fullmatch(v):
        date, clock, frac, tz = m.groups()
        clock = clock if clock.count(":") == 2 else clock + ":00"
        tz = "+00:00" if tz in (None, "Z") else (tz if ":" in tz else tz[:3] + ":" + tz[3:])
        try:
            dt = datetime.fromisoformat(f"{date}T{clock}.{(frac or '0')[:6].ljust(6, '0')}{tz}")
        except ValueError:
            return None
        # Naive timestamps from this backend are local time, not UTC.
        return (dt if m.group(4) else dt.replace(tzinfo=TZ)).astimezone(TZ)
    if m := _DE.fullmatch(v):
        d, mo, y, hh, mm = m.groups()
        try:
            return datetime(int(y), int(mo), int(d), int(hh or 0), int(mm or 0), tzinfo=TZ)
        except ValueError:
            return None
    return None


@app.template_filter("dt")
def fmt_dt(value, with_time=True) -> str:
    dt = parse_dt(value)
    if not dt:
        return value or "—"
    return dt.strftime("%d.%m.%Y, %H:%M") if with_time else dt.strftime("%d.%m.%Y")


@app.template_filter("pretty")
def fmt_json(value) -> str:
    return json.dumps(value, indent=2, ensure_ascii=False)[:20000] if value is not None else ""


# --------------------------------------------------------------------------
# PKCE + JWT helpers
# --------------------------------------------------------------------------
def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_pkce() -> tuple[str, str]:
    verifier = b64url(secrets.token_bytes(48))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    return verifier, challenge


def decode_jwt(token: str) -> dict | None:
    """Decode a JWT payload WITHOUT verifying the signature (display only)."""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        return None


def build_authorize_url(cfg: dict, state: str, challenge: str, nonce: str) -> str:
    params = {
        "client_id": cfg["client_id"], "redirect_uri": cfg["redirect_uri"],
        "response_type": "code", "scope": cfg["scope"], "state": state, "nonce": nonce,
        "code_challenge": challenge, "code_challenge_method": "S256",
    }
    if cfg.get("organization"):
        params["organization"] = cfg["organization"]
        params["client"] = cfg["client_id"]          # Mentz mirrors client_id as `client`
    if cfg.get("client_usage"):
        params["clientUsage"] = cfg["client_usage"]
    if cfg.get("app_type"):
        params["appType"] = cfg["app_type"]
    if cfg.get("prompt"):
        params["prompt"] = cfg["prompt"]
    if cfg.get("ui_locales"):
        params["ui_locales"] = cfg["ui_locales"]
    return cfg["base"].rstrip("/") + cfg["authorize_path"] + "?" + urllib.parse.urlencode(params)


def token_meta(sess: Session) -> dict:
    """Derived display info for the current token set."""
    if not sess.tokens:
        return {"logged_in": False}
    claims = decode_jwt(sess.tokens.get("access_token", "")) or {}
    exp = claims.get("exp")
    remaining = int(exp - time.time()) if exp else None
    return {
        "logged_in": True,
        "claims": claims,
        "id_claims": decode_jwt(sess.tokens.get("id_token", "")),
        "remaining": remaining,
        "expired": remaining is not None and remaining <= 0,
        "scope": sess.tokens.get("scope", ""),
        "has_refresh": bool(sess.tokens.get("refresh_token")),
    }


# --------------------------------------------------------------------------
# Token lifecycle
# --------------------------------------------------------------------------
def store_tokens(sess: Session, tokens: dict):
    sess.tokens, sess.token_received_at = tokens, time.time()
    sess.flow, sess.pending = None, {}
    sess.orders, sess.qr, sess.profile = None, {}, None
    if sess.remember:
        remember_device(sess, with_token=True)
    elif sess.device:  # login without "Angemeldet bleiben": keep only the gate
        remember.update_token(sess.device, None)


def refresh_tokens(sess: Session) -> str | None:
    """Refresh with the refresh token. Returns an error or None."""
    if not (sess.tokens and sess.tokens.get("refresh_token")):
        return "No refresh token available."
    cfg = sess.config
    try:
        r = http.post(cfg["base"].rstrip("/") + cfg["token_path"], timeout=HTTP_TIMEOUT, data={
            "grant_type": "refresh_token", "refresh_token": sess.tokens["refresh_token"],
            "client_id": cfg["client_id"], "scope": cfg["scope"],
        })
    except Exception as e:
        return f"Request failed: {e}"
    if not r.ok:
        return f"Refresh returned {r.status_code}: {r.text[:600]}"
    new = r.json()
    new.setdefault("refresh_token", sess.tokens["refresh_token"])  # some servers omit it
    sess.tokens, sess.token_received_at = new, time.time()
    if sess.device and sess.remember:
        remember.update_token(sess.device, new.get("refresh_token"))
    return None


def access_token(sess: Session) -> str | None:
    """Current access token, refreshed first if it expires within a minute."""
    if not sess.tokens:
        return None
    meta = token_meta(sess)
    if meta["remaining"] is not None and meta["remaining"] < 60 and meta["has_refresh"]:
        refresh_tokens(sess)
    return sess.tokens.get("access_token")


def new_pending(sess: Session) -> str:
    """Create a fresh PKCE/state pair (replacing any older one); return the state."""
    state, nonce = secrets.token_urlsafe(16), secrets.token_urlsafe(16)
    verifier, challenge = make_pkce()
    sess.pending = {state: {
        "verifier": verifier, "nonce": nonce, "created": time.time(),
        "authorize_url": build_authorize_url(sess.config, state, challenge, nonce),
    }}
    return state


def exchange_code(sess: Session, code: str, state: str | None) -> str | None:
    """Redeem an authorization code into `sess.tokens`. Returns an error or None."""
    pend = sess.pending.get(state) if state else None
    if pend is None and len(sess.pending) == 1:  # state not passed along
        pend = next(iter(sess.pending.values()))
    if pend is None:
        return "Could not match this callback to a pending login (state mismatch). Start login again."
    cfg = sess.config
    try:
        r = http.post(cfg["base"].rstrip("/") + cfg["token_path"], timeout=HTTP_TIMEOUT, data={
            "grant_type": "authorization_code", "code": code,
            "redirect_uri": cfg["redirect_uri"], "client_id": cfg["client_id"],
            "code_verifier": pend["verifier"],
        })
    except Exception as e:
        return f"Request failed: {e}"
    if not r.ok:
        return f"Token endpoint returned {r.status_code}: {r.text[:800]}"
    store_tokens(sess, r.json())
    return None


# --------------------------------------------------------------------------
# VRN API calls (records keep request/status/json for the debug panels)
# --------------------------------------------------------------------------
def api_call(sess: Session, method: str, url: str, payload: dict | None = None) -> dict:
    headers = {"Accept": "application/json"}
    token = access_token(sess)
    if token:
        headers["Authorization"] = "Bearer " + token
    if payload is not None:
        headers["Content-Type"] = "application/json"
    rec = {"request": f"{method} {url}" + (f"\n{json.dumps(payload, indent=2)}" if payload is not None else ""),
           "at": time.time()}
    try:
        r = http.request(method, url, headers=headers, timeout=HTTP_TIMEOUT,
                         data=json.dumps(payload) if payload is not None else None)
        rec["status"], rec["code"] = f"{r.status_code} {r.reason}", r.status_code
        try:
            rec["json"] = r.json()
        except Exception:
            rec["text"] = r.text[:20000]
    except Exception as e:
        rec["status"], rec["code"], rec["text"] = "ERROR", 0, str(e)
    return rec


def load_orders(sess: Session) -> dict:
    """POST Shop/ListOrdersV2 for active, future and expired tickets of all types."""
    now = time.time()
    rec = api_call(sess, "POST", SHOP_BASE.rstrip("/") + "/Shop/ListOrdersV2", {
        "From": vrn_client._iso(now - 365 * 86400),
        "To": vrn_client._iso(now + 400 * 86400),
        "Index": 0, "MaxNumber": 50,
        "ListOrdersTimeFilterMask": vrn_client.TIME_ACTIVE | vrn_client.TIME_FUTURE | vrn_client.TIME_HISTORIC,
        "ListOrdersTicketFilterMask": vrn_client.TICKETS_ALL,
    })
    data = rec.get("json")
    rec["orders"] = (data.get("Orders") if isinstance(data, dict) else None) or []
    err = data.get("Error") if isinstance(data, dict) else None
    rec["error"] = (err or {}).get("Message") if err else (None if rec["code"] == 200 else rec["status"])
    sess.orders = rec
    return rec


def _details(t: dict) -> list:
    d = t.get("Details")
    return d if isinstance(d, list) else [d] if isinstance(d, dict) else []


def _detail(t: dict, key: str):
    return t.get(key) or next((d.get(key) for d in _details(t) if d.get(key)), None)


GROUPS = [("current", "Aktuell gültig"), ("future", "Zukünftig gültig"),
          ("quota", "Guthaben"), ("expired", "Abgelaufen"), ("unknown", "Ohne Gültigkeitsdatum")]
# Shown without debug mode; expired/undated tickets only appear in debug mode.
VISIBLE_GROUPS = {"current", "future", "quota"}


def _first_date(t: dict, *keys) -> tuple[str | None, datetime | None]:
    """First parseable date among ticket-level and Details fields, with its raw value."""
    for k in keys:
        for src in [t] + _details(t):
            raw = src.get(k)
            dt = parse_dt(raw)
            if dt:
                return raw, dt
    return None, None


def ticket_view(order: dict, t: dict) -> dict:
    """One ticket card/detail model from OrderResult + OrderResultTicket."""
    now = datetime.now(TZ)
    start_raw, start = _first_date(t, "ValidFrom", "ActivationFrom")
    end_raw, end = _first_date(t, "ValidTo", "ValidUntil", "ActivationUntil")
    name = _detail(t, "TicketName") or order.get("Product") or "Ticket"
    allowed = t.get("AllowedQuota")
    # -1 means "not a quota ticket" (e.g. Deutschland-Semesterticket), so only positive counts.
    if isinstance(allowed, (int, float)) and allowed > 0:
        group = "quota"
    elif end is None:
        group = "unknown"
    elif end < now:
        group = "expired"
    elif start and start > now:
        group = "future"
    else:
        group = "current"
    return {
        "id": t.get("Id"), "name": name, "group": group,
        "valid_from": start_raw, "valid_to": end_raw, "start": start, "end": end,
        "dticket": "deutschland" in name.lower(),
        "price_level": _detail(t, "PriceLevel"),
        "area": _detail(t, "AreaName") or _detail(t, "TariffArea"),
        "holder": _detail(t, "Holder"), "dob": _detail(t, "Dob"),
        "number": _detail(t, "AuthorizationNumber") or order.get("OrderNumber"),
        "order_id": order.get("Id"), "order_number": order.get("OrderNumber"), "booked": order.get("BookingDate"),
        "quota": (t.get("UsedQuota"), t.get("AllowedQuota")),
        "hidden": bool(t.get("Canceled") or t.get("Refunded")),
        "order": order, "raw": t,
    }


def grouped_tickets(orders: list, debug: bool = False) -> tuple[list, int]:
    """Tickets grouped for the list; returns (groups, number of tickets hidden)."""
    tickets = [ticket_view(o, t) for o in orders for t in (o.get("Tickets") or [])]
    tickets = [t for t in tickets if debug or not t["hidden"]]
    far = datetime.max.replace(tzinfo=TZ)
    order_by = {
        "current": lambda t: t["end"] or far,           # expiring soonest first
        "future": lambda t: t["start"] or far,          # starting soonest first
        "quota": lambda t: t["end"] or far,
        "expired": lambda t: -(t["end"].timestamp()),   # most recent first
        "unknown": lambda t: str(t["id"]),
    }
    out, hidden = [], 0
    for key, label in GROUPS:
        items = sorted((t for t in tickets if t["group"] == key), key=order_by[key])
        if not debug and key not in VISIBLE_GROUPS:
            hidden += len(items)
            continue
        if items:
            out.append({"key": key, "label": label, "tickets": items})
    return out, hidden


def find_ticket(sess: Session, tid: int) -> dict | None:
    for o in (sess.orders or {}).get("orders") or []:
        for t in o.get("Tickets") or []:
            if t.get("Id") == tid:
                return ticket_view(o, t)
    return None


def barcode_kind(code: str) -> str:
    """'png'/'jpeg'/'svg' for base64 images, else 'data' (render client-side)."""
    c = (code or "").strip()
    if c.startswith("iVBOR"):
        return "png"
    if c.startswith("/9j/"):
        return "jpeg"
    if c.startswith("PHN2Zy") or c.startswith("<svg"):
        return "svg"
    return "data"


def fetch_qr(sess: Session, tid: int, force: bool = False) -> dict:
    """POST Shop/QRCode for one ticket (cached per session)."""
    rec = sess.qr.get(tid)
    if rec is None or force:
        rec = api_call(sess, "POST", SHOP_BASE.rstrip("/") + "/Shop/QRCode", {"Id": tid})
        data = rec.get("json") if isinstance(rec.get("json"), dict) else {}
        rec["code_data"] = data.get("QrCode") or ""
        rec["kind"] = barcode_kind(rec["code_data"])
        rec["error"] = (data.get("Error") or {}).get("Message")
        sess.qr[tid] = rec
    return rec


# Offline copy: valid tickets incl. QR code, stored by the browser (localStorage) so the
# PWA can show them without network. At most this many tickets get their QR prefetched.
OFFLINE_MAX_TICKETS = int(os.environ.get("OFFLINE_MAX_TICKETS", "6"))


def offline_enabled() -> bool:
    return request.cookies.get("offline", "1") != "0"


def offline_snapshot(sess: Session) -> dict | None:
    """Current/future tickets with their QR codes for the browser's offline store."""
    if not (sess.tokens and offline_enabled() and sess.orders):
        return None
    groups, _ = grouped_tickets(sess.orders.get("orders") or [])
    tickets = [t for g in groups if g["key"] in ("current", "future", "quota") for t in g["tickets"]]
    out = []
    for t in tickets[:OFFLINE_MAX_TICKETS]:
        rec = fetch_qr(sess, t["id"]) if t["id"] is not None else {}
        out.append({
            "id": t["id"], "name": t["name"], "dticket": t["dticket"],
            "start": t["start"].isoformat() if t["start"] else None,
            "end": t["end"].isoformat() if t["end"] else None,
            "area": t["area"], "price_level": t["price_level"], "holder": t["holder"],
            "dob": fmt_dt(t["dob"], False) if t["dob"] else None, "number": t["number"],
            "qr": rec.get("code_data") or None, "kind": rec.get("kind"),
        })
    prof = (sess.profile or {}).get("profile") or {}
    claims = token_meta(sess).get("claims") or {}
    return {
        "savedAt": datetime.now(TZ).isoformat(),
        "user": {"sub": claims.get("sub"), "first": prof.get("first"),
                 "name": " ".join(x for x in (prof.get("first"), prof.get("last")) if x) or None,
                 "email": prof.get("email")},
        "tickets": out,
    }


# --------------------------------------------------------------------------
# PWA: manifest, service worker, offline page (no personal data in any of them)
# --------------------------------------------------------------------------
@app.route("/manifest.webmanifest")
def manifest():
    resp = send_from_directory(app.static_folder, "manifest.webmanifest", mimetype="application/manifest+json")
    resp.headers["Cache-Control"] = "no-cache"
    return resp


@app.route("/sw.js")
def service_worker():
    # Served from the root so its scope covers the whole app.
    resp = send_from_directory(app.static_folder, "sw.js", mimetype="text/javascript")
    resp.headers["Cache-Control"] = "no-cache"
    resp.headers["Service-Worker-Allowed"] = "/"
    return resp


@app.route("/offline")
def offline():
    # Static shell; the tickets come from the browser's own offline store.
    resp = make_response(render_template("offline.html", title="Tickets", active="tickets", meta={"logged_in": False}))
    resp.headers["Cache-Control"] = "no-cache"
    return resp


# --------------------------------------------------------------------------
# Tickets
# --------------------------------------------------------------------------
@app.route("/")
def index():
    return redirect(url_for("tickets"))


@app.route("/tickets")
def tickets():
    sess, sid = get_session()
    if sess.tokens and (sess.orders is None or request.args.get("reload")):
        load_orders(sess)
        if request.args.get("reload"):
            return go(sid, "tickets")
    orders = (sess.orders or {}).get("orders") or []
    groups, hidden = grouped_tickets(orders, debug=request.cookies.get("debug") == "1")
    return page(sess, sid, "tickets.html", active="tickets", title="Tickets",
                groups=groups, hidden=hidden, rec=sess.orders)


@app.route("/tickets/offline.json")
def offline_data():
    """Offline copy, fetched in the background after a page has rendered (keeps pages fast:
    prefetching QR codes inline made the first load slow enough to trip the service
    worker's offline timeout)."""
    sess, sid = get_session()
    if sess.tokens and sess.orders is None:
        load_orders(sess)
    resp = make_response(jsonify(offline_snapshot(sess) or {}))
    resp.headers["Cache-Control"] = "no-store"
    return attach_cookie(resp, sid)


@app.route("/tickets/<int:tid>")
def ticket_detail(tid: int):
    sess, sid = get_session()
    if not sess.tokens:
        return go(sid, "account")
    if sess.orders is None:
        load_orders(sess)
    ticket = find_ticket(sess, tid)
    if ticket is None:
        abort(404)
    rec = fetch_qr(sess, tid, force=bool(request.args.get("reload")))
    if request.args.get("reload"):
        return go(sid, "ticket_detail", tid=tid)
    invoice = sess.invoice_rec if sess.invoice_rec and sess.invoice_rec.get("tid") == tid else None
    return page(sess, sid, "ticket.html", active="tickets", title="Ticketdetails",
                back=url_for("tickets"), t=ticket, qr=rec, invoice=invoice)


@app.route("/tickets/<int:tid>/rechnung")
def ticket_invoice(tid: int):
    """The ticket's order invoice as a PDF (the official app's "Rechnung herunterladen").
    Kept in the session once fetched; on failure back to the ticket with the error."""
    sess, sid = get_session()
    if not sess.tokens:
        return go(sid, "account")
    if sess.orders is None:
        load_orders(sess)
    ticket = find_ticket(sess, tid)
    if ticket is None or not ticket["order_id"]:
        abort(404)
    pdf = sess.invoices.get(ticket["order_id"])
    if pdf is None:
        rec = api_call(sess, "POST", shop.INVOICE_URL, {"TicketOrderId": [ticket["order_id"]], "IsGuestUser": False})
        pdf, error = shop.invoice_pdf(rec)
        sess.invoice_rec = {"tid": tid, "error": error, "request": rec.get("request"), "status": rec.get("status"),
                            "response": (rec.get("text") or json.dumps(rec.get("json")))[:2000]}
        if pdf is None:
            data = rec.get("json")
            shape = {k: (type(v).__name__, len(v) if isinstance(v, (str, list, dict)) else v if v is None or isinstance(v, (bool, int)) else "…")
                     for k, v in data.items()} if isinstance(data, dict) else type(data).__name__
            print(f"invoice: order {ticket['order_id']} -> {rec.get('status')} {error!r} {shape}", file=sys.stderr, flush=True)
            return go(sid, "ticket_detail", tid=tid, _anchor="rechnung")
        sess.invoices[ticket["order_id"]] = pdf
    name = "Rechnung-" + re.sub(r"[^A-Za-z0-9-]", "_", str(ticket["order_number"] or ticket["order_id"])) + ".pdf"
    resp = make_response(pdf)
    resp.headers["Content-Type"] = "application/pdf"
    resp.headers["Content-Disposition"] = f'inline; filename="{name}"'
    return attach_cookie(resp, sid)


# --------------------------------------------------------------------------
# Account (login + profile)
# --------------------------------------------------------------------------
def _pending_url(sess: Session) -> str | None:
    return next(iter(sess.pending.values()))["authorize_url"] if sess.pending else None


@app.route("/account")
def account():
    sess, sid = get_session()
    if sess.tokens and (sess.profile is None or request.args.get("reload")):
        sess.profile = load_profile(sess)
        if request.args.get("reload"):
            return go(sid, "account")
    return account_page(sess, sid)


def _ci(d: dict, *keys):
    """Case-insensitive lookup (the TicketShop and Identity APIs differ in casing)."""
    if not isinstance(d, dict):
        return None
    low = {k.lower(): v for k, v in d.items()}
    for k in keys:
        v = low.get(k.lower())
        if v not in (None, ""):
            return v
    return None


def load_profile(sess: Session) -> dict:
    """Profile for the Konto page.

    The Identity OData profile answers 403 for the app's `ticketing` token, so fall back to
    the customer record the app uses when buying tickets (Payment/Customer/PaymentInfos). Returns {"profile", "source", "calls"}.
    """
    calls = [api_call(sess, "GET", PROFILE_URL)]
    data = calls[0].get("json")
    if calls[0]["code"] == 200 and isinstance(data, dict):
        prof = (data.get("value") or [None])[0] if "value" in data else data
        if isinstance(prof, dict):
            user = prof.get("User") if isinstance(prof.get("User"), dict) else {}
            return {"source": "Identity/OData/UserProfile", "calls": calls, "profile": {
                "title": _ci(prof, "Title"), "first": _ci(prof, "FirstName"), "last": _ci(prof, "LastName"),
                "email": _ci(prof, "Email") or user.get("Name"), "dob": _ci(prof, "DateOfBirth"),
                "street": _ci(prof, "StreetAddress"), "zip": _ci(prof, "PostalCode"),
                "city": _ci(prof, "Locality"), "phone": _ci(prof, "Phone")}}
    # The app's CustomerInfo comes from GET {baseUrl}/Payment/Customer/PaymentInfos
    # ("Shop/CustomerInfo" is only its cache key) -> {Address: {FirstName, …}, …}.
    rec = api_call(sess, "GET", PAYMENT_INFO_URL)
    calls.append(rec)
    data = rec.get("json")
    addr = _ci(data, "Address") if isinstance(data, dict) else None
    if rec["code"] == 200 and isinstance(addr, dict):
        return {"source": "Payment/Customer/PaymentInfos", "calls": calls, "profile": {
            "title": None, "first": _ci(addr, "FirstName"), "last": _ci(addr, "LastName"),
            "email": _ci(addr, "Email"), "dob": _ci(addr, "Dob", "DateOfBirth"),
            "street": _ci(addr, "StreetAddress"), "zip": _ci(addr, "PostalCode"),
            "city": _ci(addr, "Locality"), "phone": None}}
    return {"source": None, "calls": calls, "profile": None}


def account_page(sess: Session, sid: str, **kw):
    rec = sess.profile or {}
    return page(sess, sid, "account.html", active="account", title="Konto",
                profile=rec.get("profile"), profile_rec=rec,
                pending_url=_pending_url(sess), need_code=bool(sess.flow),
                remembered=bool(sess.device and sess.remember), **kw)


@app.route("/account/login", methods=["POST"])
def login_direct():
    sess, sid = get_session()
    email = (request.form.get("email") or "").strip().lower()
    if not sec.login_by_ip.hit(sec.client_ip()) or not sec.login_by_account.hit(email):
        return account_page(sess, sid, error="Zu viele Anmeldeversuche. Bitte in 15 Minuten erneut versuchen.",
                            email=email)
    sess.remember = request.form.get("remember") == "1"
    flow = vrn_client.LoginFlow(impersonate=IMPERSONATE, timeout=HTTP_TIMEOUT)
    try:
        res = flow.start(request.form.get("email", ""), request.form.get("password", ""))
    except vrn_client.VrnError as e:
        msg = {"CredentialValidationFailed": "E-Mail oder Passwort ist falsch.",
               "UserLocked": "Dein Konto ist gesperrt.",
               "UserNotActivated": "Dein Konto ist noch nicht aktiviert."}.get(str(e), f"Login fehlgeschlagen: {e}")
        return account_page(sess, sid, error=msg, email=request.form.get("email", ""))
    except Exception as e:
        return account_page(sess, sid, error=f"Login fehlgeschlagen: {e}")
    if res["next"] == "code":
        sess.flow = flow
        return account_page(sess, sid, email=request.form.get("email", ""))
    store_tokens(sess, res["tokens"])
    return go(rotate_session(sid), "tickets")


@app.route("/account/code", methods=["POST"])
def login_direct_code():
    sess, sid = get_session()
    if not sess.flow:
        return account_page(sess, sid, error="Kein laufender Login — bitte erneut einloggen.")
    if not sec.login_by_ip.hit(sec.client_ip()):
        return account_page(sess, sid, error="Zu viele Versuche. Bitte in 15 Minuten erneut versuchen.")
    try:
        res = sess.flow.finalize(request.form.get("code", ""))
    except Exception as e:
        return account_page(sess, sid, error=f"Code abgelehnt: {e}")
    store_tokens(sess, res["tokens"])
    return go(rotate_session(sid), "tickets")


@app.route("/account/cancel", methods=["POST"])
def login_cancel():
    sess, sid = get_session()
    sess.flow = None
    return go(sid, "account")


@app.route("/account/logout", methods=["POST"])
def logout():
    sess, sid = get_session()
    gate_passed, device = sess.gate_ok, sess.device
    if device:
        if sec.ACCESS_PASSWORD:
            remember.update_token(device, None)  # stay past the gate, but VRN login is gone
        else:
            remember.forget(device)
            device = None
    with SESSIONS_LOCK:
        SESSIONS.pop(sid, None)  # forget tokens and data; the next request gets a fresh session
    g.pop("sess", None)
    g.pop("sid", None)
    new_sess, new_sid = get_session()
    new_sess.gate_ok = gate_passed and sid != new_sid
    new_sess.device, new_sess.device_clear = device, device is None
    return go(new_sid, "account", loggedout=1)


@app.route("/account/refresh", methods=["POST"])
def refresh():
    sess, sid = get_session()
    err = refresh_tokens(sess)
    return account_page(sess, sid, error=err) if err else go(sid, "account")


# Manual flow (debug): generate the authorize URL, paste the vrn:// callback back.
@app.route("/account/manual/start", methods=["POST"])
def login_start():
    sess, sid = get_session()
    new_pending(sess)
    return go(sid, "account", _anchor="manual")


def _parse_callback(pasted: str) -> tuple[str | None, str | None]:
    """Accept a full callback URL, a query string, or a bare code."""
    if not pasted:
        return None, None
    query = pasted.split("?", 1)[1] if "?" in pasted else pasted.split("#", 1)[1] if "#" in pasted else pasted
    parsed = urllib.parse.parse_qs(query)
    if "code" in parsed:
        return parsed["code"][0], parsed.get("state", [None])[0]
    if "=" not in pasted and "&" not in pasted and len(pasted) > 8:
        return pasted, None
    return None, None


@app.route("/account/manual/complete", methods=["POST"])
def login_complete():
    sess, sid = get_session()
    pasted = (request.form.get("callback") or "").strip()
    code, state = _parse_callback(pasted)
    err = exchange_code(sess, code, state) if code else "Kein `code` gefunden — die ganze vrn://oauth/callback?… URL einfügen."
    return account_page(sess, sid, error=err, callback_value=pasted) if err else go(rotate_session(sid), "tickets")


# Remote WebView login (debug): Chromium on the container's display via noVNC.
def vnc_url() -> str:
    host = urllib.parse.urlsplit(request.host_url).hostname
    if ":" in host:  # IPv6 literal
        host = f"[{host}]"
    return f"http://{host}:{VNC_PORT}/vnc.html?autoconnect=1&resize=scale&reconnect=1"


@app.route("/account/remote", methods=["POST"])
def login_remote():
    sess, sid = get_session()
    state = new_pending(sess)
    remote.start(sess.pending[state]["authorize_url"], sess.config["redirect_uri"],
                 lambda code, st: exchange_code(sess, code, st))
    return go(sid, "login_remote_view")


@app.route("/account/remote")
def login_remote_view():
    sess, sid = get_session()
    return page(sess, sid, "remote.html", active="account", title="Remote WebView",
                back=url_for("account"), vnc_url=vnc_url(), status=remote.status)


@app.route("/account/remote/status")
def login_remote_status():
    return jsonify(remote.status)


@app.route("/account/remote/stop", methods=["POST"])
def login_remote_stop():
    remote.stop()
    return redirect(url_for("account"))


# --------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------
@app.route("/settings", methods=["GET", "POST"])
def settings():
    sess, sid = get_session()
    if request.method == "POST":
        resp = go(sid, "settings")
        if request.form.get("theme") in THEMES:
            resp.set_cookie("theme", request.form["theme"], max_age=365 * 86400, samesite="Lax",
                            secure=sec.COOKIE_SECURE)
        if request.form.get("palette") in PALETTES:
            resp.set_cookie("palette", request.form["palette"], max_age=365 * 86400, samesite="Lax",
                            secure=sec.COOKIE_SECURE)
        if "offline" in request.form:
            resp.set_cookie("offline", "1" if request.form["offline"] == "1" else "0",
                            max_age=365 * 86400, samesite="Lax", secure=sec.COOKIE_SECURE)
        if "debug" in request.form:
            resp.set_cookie("debug", "1" if request.form["debug"] == "1" else "0",
                            max_age=365 * 86400, samesite="Lax", secure=sec.COOKIE_SECURE)
        return resp
    return page(sess, sid, "settings.html", active="settings", title="Einstellungen",
                note=request.args.get("note"), probe=sess.probe,
                suggestions=PROBE_SUGGESTIONS, session_discovery=sess.discovery)


@app.route("/settings/config", methods=["POST"])
def config():
    sess, sid = get_session()
    for k in sess.config:
        if k in request.form:
            sess.config[k] = request.form[k].strip()
    return go(sid, "settings", note="Konfiguration gespeichert.", _anchor="config")


@app.route("/settings/discover", methods=["POST"])
def discover():
    sess, sid = get_session()
    cfg = sess.config
    try:
        r = http.get(cfg["base"].rstrip("/") + cfg["discovery_path"], timeout=HTTP_TIMEOUT)
        if r.ok:
            d = sess.discovery = r.json()
            if d.get("authorization_endpoint"):
                cfg["authorize_path"] = urllib.parse.urlparse(d["authorization_endpoint"]).path
            if d.get("token_endpoint"):
                cfg["token_path"] = urllib.parse.urlparse(d["token_endpoint"]).path
        else:
            sess.discovery = {"error": f"{r.status_code}", "body": r.text[:600]}
    except Exception as e:
        sess.discovery = {"error": str(e)}
    return go(sid, "settings", _anchor="config")


PROBE_SUGGESTIONS = [
    ("GET", "https://shop.myvrn.de/Identity/connect/userinfo"),
    ("GET", "https://shop.myvrn.de/Payment/Customer/PaymentInfos"),
    ("GET", "https://shop.myvrn.de/Personalization/OData/TicketEntitlement?$expand=Ticket"),
]


@app.route("/settings/probe", methods=["POST"])
def probe():
    sess, sid = get_session()
    method = (request.form.get("method") or "GET").upper()
    body = (request.form.get("body") or "").strip()
    try:
        payload = json.loads(body) if body else ({} if method == "POST" else None)
    except ValueError:
        payload = None
    sess.probe = api_call(sess, method, (request.form.get("url") or "").strip(), payload)
    return go(sid, "settings", _anchor="probe")


# --------------------------------------------------------------------------
# Ticketshop: entitlements (Berechtigungsnachweise) and buying (see shop.py)
# --------------------------------------------------------------------------
SNAPSHOT_PATH = os.environ.get("SHOP_SNAPSHOT", "/data/shop-snapshot.json")


def shop_state(sess: Session) -> dict:
    """Everything the Ticketshop page needs: entitlements, Semesterticket history, what to offer."""
    now = datetime.now(TZ)
    items, status = shop.entitlements(lambda m, u, p=None: api_call(sess, m, u, p))
    current, latest = shop.student_entitlement(items or [], now)
    if sess.orders is None:
        load_orders(sess)
    tickets = shop.semester_tickets((sess.orders or {}).get("orders") or [])
    this_month = next((t for t in tickets if t["from"] and t["to"] and t["from"] <= now < t["to"]), None)
    # The subscription renews itself monthly (orders around the 29th): a Semesterticket that starts
    # within the current entitlement's period means it's running, so never offer a second one.
    # (Last semester's final ticket ends a few hours into the new period: it doesn't count.)
    since = shop._ts(current.get("EligibleFrom")) if current else None
    running = next((t for t in tickets if since and t["from"] and t["from"] >= since - timedelta(hours=1)), None)
    return {
        "now": now, "items": items, "status": status, "current": current, "latest": latest,
        "tickets": tickets, "this_month": this_month, "running": running,
        "student": shop.student_entitlements(items or []),
        "other_count": len([e for e in items or [] if shop.ticket_type(e) != shop.STUDENT_ANNOTATION]),
        "can_buy": bool(current) and running is None,
        "month": shop.month_start(now),
        "status_name": lambda e: shop.STATUS_LABELS.get(shop.STATUS_NAMES.get(e.get("Status"), "unknown"), "?"),
    }


def shop_authorities(sess: Session) -> list[dict]:
    """Universities for the Hochschul-Login (Dfn/GetAuthorities, cached per session)."""
    if sess.shop_unis is None:
        rec = api_call(sess, "GET", shop.AUTHORITIES_URL)
        sess.shop_unis = shop.authorities(rec.get("json")) if rec.get("code") == 200 else []
    return sess.shop_unis


@app.route("/ticketshop")
def ticketshop():
    sess, sid = get_session()
    st = shop_state(sess) if sess.tokens else None
    unis = shop_authorities(sess) if sess.tokens else []
    return page(sess, sid, "ticketshop.html", active="shop", title="Ticketshop", st=st, unis=unis,
                result=sess.shop_result, saved=request.args.get("saved"), verified=request.args.get("verifiziert"))


@app.route("/ticketshop/verifizieren")
def ticketshop_verify():
    """Hochschul-Login: to the shop's login, which continues to the chosen university."""
    sess, sid = get_session()
    entity = request.args.get("uni", "")
    if not sess.tokens or entity not in {u["id"] for u in shop_authorities(sess)}:
        return go(sid, "ticketshop")
    back = url_for("ticketshop", verifiziert=1, _external=True)
    return attach_cookie(make_response(redirect(shop.verify_url(entity, back))), sid)


def _semester_order(sess: Session, st: dict) -> dict:
    prof = (load_profile(sess) if sess.profile is None else sess.profile).get("profile") or {}
    template = st["tickets"][0] if st["tickets"] else None
    detail = ((template or {}).get("ticket") or {}).get("Details") or [{}]
    person = {"first": prof.get("first"), "last": prof.get("last"), "gender": (detail[0] or {}).get("Gender"),
              "dob": (detail[0] or {}).get("Dob") or prof.get("dob")}
    return shop.build_semester_order(template, person, st["now"])


@app.route("/ticketshop/semesterticket", methods=["GET", "POST"])
def ticketshop_buy():
    """Deutschland-Semesterticket for this month: confirm (GET), then order (POST)."""
    sess, sid = get_session()
    if not sess.tokens:
        return go(sid, "account")
    st = shop_state(sess)
    if not st["can_buy"]:
        return go(sid, "ticketshop")
    payload = _semester_order(sess, st)
    if request.method == "GET":
        sess.shop_result = None
        return page(sess, sid, "ticketshop_confirm.html", active="shop", title="Ticketkauf überprüfen",
                    back=url_for("ticketshop"), st=st, payload=payload)
    if request.form.get("agb") != "1":
        return page(sess, sid, "ticketshop_confirm.html", active="shop", title="Ticketkauf überprüfen",
                    back=url_for("ticketshop"), st=st, payload=payload, error="Bitte die AGB bestätigen.")
    rec = api_call(sess, "POST", shop.CREATE_ORDER_URL, payload)
    data = rec.get("json") if isinstance(rec.get("json"), dict) else {}
    err = data.get("Error") or {}
    if data.get("Orders"):
        result = {"ok": True, "text": "Dein Deutschland-Semesterticket wurde gebucht."}
    elif data.get("RedirectUrl"):
        # A payment step: never followed here (the Semesterticket is free; something is off).
        result = {"ok": False, "text": "Der Shop verlangt eine Zahlung. Abgebrochen: das Semesterticket sollte 0 € kosten."}
    else:
        result = {"ok": False, "text": "Kauf fehlgeschlagen: " + (err.get("Message") or rec.get("status") or "unbekannter Fehler")
                  + (f" (Code {err.get('MessageID')})" if err.get("MessageID") else "")}
    result["request"] = rec.get("request")
    result["response"] = rec.get("json") if rec.get("json") is not None else rec.get("text")
    sess.shop_result = result
    load_orders(sess)  # the new ticket shows up in "Meine Tickets"
    return go(sid, "ticketshop", _anchor="result")


@app.route("/ticketshop/snapshot", methods=["POST"])
def ticketshop_snapshot():
    """Debug: save the read-only shop answers (entitlements, universities, products, orders)
    on the server for analysis. Only on the user's own tap; contains personal data."""
    sess, sid = get_session()
    if not sess.tokens:
        return go(sid, "account")
    data = shop.snapshot(lambda m, u, p=None: api_call(sess, m, u, p))
    orders = load_orders(sess)
    data["orders"] = {"status": orders.get("status"), "json": orders.get("json")}
    os.makedirs(os.path.dirname(SNAPSHOT_PATH), exist_ok=True)
    fd = os.open(SNAPSHOT_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    return go(sid, "ticketshop", saved=1)


# --------------------------------------------------------------------------
# Fahrplan: connection search via VRN's EFA journey planner (see efa.py)
# --------------------------------------------------------------------------
def _search_params() -> dict:
    """The search as given in the query string, with defaults (now, departure)."""
    a = request.args
    now = efa.now_local()
    return {
        "from": a.get("from", "").strip(), "from_name": a.get("from_name", "").strip(),
        "to": a.get("to", "").strip(), "to_name": a.get("to_name", "").strip(),
        "date": a.get("date") or now.strftime("%Y-%m-%d"),
        "time": a.get("time") or now.strftime("%H:%M"),
        "arr": a.get("arr") == "1",
    }


def _search_when(p: dict) -> datetime:
    try:
        return datetime.strptime(f"{p['date']} {p['time']}", "%Y-%m-%d %H:%M").replace(tzinfo=TZ)
    except ValueError:
        return efa.now_local().replace(second=0, microsecond=0)


def _resolve(place_id: str, name: str) -> tuple[str, str]:
    """An id for the trip request: the chosen suggestion, else the best stop-finder match."""
    if place_id:
        return place_id, name
    hits = efa.stops(http.get, name, timeout=HTTP_TIMEOUT, limit=3)
    if not hits:
        raise efa.EfaError(f"„{name}“ wurde nicht gefunden.")
    best = next((h for h in hits if h["best"]), hits[0])
    return best["id"], best["name"]


def run_search(sess: Session, p: dict) -> dict:
    """Trip request for the search parameters, cached per session for the detail view."""
    key = (p["from"], p["to"], p["date"], p["time"], p["arr"])
    cached = sess.trips
    if cached and cached["key"] == key and time.time() - cached["at"] < 120:
        return cached
    rec = {"key": key, "at": time.time(), "journeys": [], "error": None, "url": None, "raw": None}
    try:
        res = efa.trips(http.get, p["from"], p["to"], _search_when(p), arrive=p["arr"], timeout=HTTP_TIMEOUT)
        rec.update(journeys=res["journeys"], url=res["url"], raw=res["raw"])
    except efa.EfaError as e:
        rec["error"] = str(e)
    except Exception as e:  # network
        rec["error"] = f"VRN-Fahrplanauskunft nicht erreichbar ({type(e).__name__})."
    if not rec["error"]:
        sess.trips = rec
    return rec


def _query(p: dict) -> dict:
    """Search parameters as URL arguments."""
    q = {k: p[k] for k in ("from", "from_name", "to", "to_name", "date", "time")}
    return q | {"arr": "1"} if p["arr"] else q


def _shifted(p: dict, when: datetime) -> dict:
    """The same search as a departure search at another time (Früher/Später)."""
    return _query(p | {"date": when.strftime("%Y-%m-%d"), "time": when.strftime("%H:%M"), "arr": False})


@app.route("/fahrplan")
def connections():
    sess, sid = get_session()
    p = _search_params()
    rec, error = None, None
    if (p["from"] or p["from_name"]) and (p["to"] or p["to_name"]):
        try:
            p["from"], p["from_name"] = _resolve(p["from"], p["from_name"])
            p["to"], p["to_name"] = _resolve(p["to"], p["to_name"])
        except efa.EfaError as e:
            error = str(e)
        except Exception as e:
            error = f"VRN-Fahrplanauskunft nicht erreichbar ({type(e).__name__})."
        if not error:
            rec = run_search(sess, p)
            error = rec["error"]
    elif request.args.get("from_name") is not None:
        error = "Bitte Start und Ziel angeben."
    journeys = (rec or {}).get("journeys") or []
    earlier = later = None
    if journeys:
        earlier = _shifted(p, (journeys[0]["dep"] or _search_when(p)) - timedelta(minutes=45))
        later = _shifted(p, (journeys[-1]["dep"] or _search_when(p)) + timedelta(minutes=1))
    return page(sess, sid, "connections.html", active="fahrplan", title="Fahrplan", p=p, error=error,
                journeys=journeys, rec=rec, earlier=earlier, later=later, query=_query(p),
                pins=_pins(p, journeys))


def _pins(p: dict, journeys: list) -> dict:
    """Origin/destination coordinates for the map pins: from the trip, else from coord ids."""
    pins = {}
    for key, end in (("from", "origin"), ("to", "destination")):
        leg = journeys[0]["legs"][0 if key == "from" else -1] if journeys else None
        coord = leg[end]["coord"] if leg else None
        if not coord and p[key].startswith("coord:"):
            coord = [float(x) for x in p[key][6:].split(",")]
        if coord:
            pins[key] = {"coord": coord, "name": p[key + "_name"]}
    return pins


@app.route("/fahrplan/verbindung/<int:idx>")
def connection_detail(idx: int):
    sess, sid = get_session()
    p = _search_params()
    if not (p["from"] and p["to"]):
        return go(sid, "connections")
    rec = run_search(sess, p)
    journeys = rec.get("journeys") or []
    if idx >= len(journeys):
        return go(sid, "connections", **_query(p))
    j = journeys[idx]
    # Map data: one polyline per leg (falls back to the stop coordinates), plus the stops.
    map_legs = []
    for leg in j["legs"]:
        pts = leg["coords"] or [s["coord"] for s in [leg["origin"], *leg["stops"], leg["destination"]] if s["coord"]]
        map_legs.append({"mode": leg["mode"], "line": leg["line"], "coords": pts,
                         "from": leg["origin"]["name"], "to": leg["destination"]["name"],
                         "stops": [{"name": s["name"], "coord": s["coord"]} for s in leg["stops"] if s["coord"]],
                         "start": leg["origin"]["coord"], "end": leg["destination"]["coord"]})
    return page(sess, sid, "connection.html", active="fahrplan", title="Verbindung",
                back=url_for("connections", **_query(p)), j=j, p=p, map_legs=map_legs, rec=rec)


@app.route("/fahrplan/orte")
def connection_places():
    q = (request.args.get("q") or "").strip()
    if len(q) < 2:
        return jsonify([])
    try:
        return jsonify(efa.stops(http.get, q[:80], timeout=HTTP_TIMEOUT))
    except Exception:
        return jsonify([]), 502


@app.route("/fahrplan/hier")
def connection_here():
    """Long-press on the map: the address there and the nearest stops."""
    try:
        lat, lon = float(request.args["lat"]), float(request.args["lon"])
    except (KeyError, ValueError):
        abort(400)
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        abort(400)
    try:
        return jsonify(efa.here(http.get, lat, lon, timeout=HTTP_TIMEOUT))
    except Exception:
        return jsonify({"place": {"id": efa.coord_id(lat, lon), "name": "Punkt auf der Karte",
                                  "coord": [lat, lon]}, "stops": []}), 502


@app.template_filter("hm")
def fmt_hm(value) -> str:
    return value.strftime("%H:%M") if value else "—"


@app.template_filter("duration")
def fmt_duration(minutes) -> str:
    if minutes is None:
        return "—"
    h, m = divmod(int(minutes), 60)
    return f"{h}:{m:02d} h" if h else f"{m} min"


# --------------------------------------------------------------------------
# Android app download
# --------------------------------------------------------------------------
# Drop the built APK into this folder (docker-compose mounts ./downloads); the newest
# *.apk is offered on /app, older ones are listed below it.
APK_DIR = os.environ.get("APK_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "downloads"))
APK_MIME = "application/vnd.android.package-archive"
_apk_hashes: dict[tuple[str, int, int], str] = {}  # (name, mtime_ns, size) -> sha256


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def list_apks() -> list[dict]:
    """APKs in APK_DIR, newest first, with size, date and SHA-256."""
    try:
        names = [n for n in os.listdir(APK_DIR) if n.lower().endswith(".apk") and not n.startswith(".")]
    except OSError:
        return []
    apks = []
    for name in names:
        path = os.path.join(APK_DIR, name)
        try:
            st = os.stat(path)
            if not stat.S_ISREG(st.st_mode):
                continue
            key = (name, st.st_mtime_ns, st.st_size)
            if key not in _apk_hashes:
                _apk_hashes[key] = _sha256(path)
        except OSError:
            continue
        apks.append({"name": name, "size_mb": st.st_size / 1e6, "sha256": _apk_hashes[key],
                     "date": datetime.fromtimestamp(st.st_mtime, TZ).strftime("%d.%m.%Y, %H:%M"),
                     "mtime": st.st_mtime})
    return sorted(apks, key=lambda a: a["mtime"], reverse=True)


@app.route("/app")
def android_app():
    sess, sid = get_session()
    ua = request.headers.get("User-Agent", "")
    return page(sess, sid, "app.html", active="app", title="Android-App", apks=list_apks(),
                ios=bool(re.search(r"iPhone|iPad|iPod", ua)))


@app.route("/app/download/<name>")
def android_app_file(name: str):
    if name not in {a["name"] for a in list_apks()}:
        abort(404)
    resp = send_from_directory(APK_DIR, name, as_attachment=True, mimetype=APK_MIME, max_age=0)
    resp.headers["Cache-Control"] = "no-cache"
    return resp


@app.route("/healthz")
def healthz():
    return {"ok": True}


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    app.run(host="0.0.0.0", port=port, debug=bool(os.environ.get("FLASK_DEBUG")))
