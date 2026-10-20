"""
Browserless myVRN client — the complete login + ticket flow as plain HTTP.

Reverse-engineered from the login SPA (shop.myvrn.de/static/js, AuthenticationPublic*)
and the APK's native net.mentz.ticketing library. This module is the reference
for ports (Android app, PWA proxy): every request the flow needs is here.

Flow
  1. GET  /Identity/connect/authorize?…PKCE…          -> 302 /authentication/login?returnUrl=…
  2. POST /Identity/Authentication/Login              {Name, Password, Client, Organization, RememberLogin}
        -> {Succeeded, TwoFactorProvider, CredentialValidationFailed, …} + auth cookie
  3. (TwoFactorProvider == 2, email) POST /Identity/Authentication/FinalizeLogin  "<code>"
  4. GET  {returnUrl} (= /Identity/connect/authorize/callback?…) with the cookie
        -> 302 vrn://oauth/callback?code=…&state=…
  5. POST /Identity/connect/token  (authorization_code + code_verifier)
  6. POST /TicketShop/Shop/ListOrdersV2 / Shop/QRCode with Bearer token

The server stalls non-browser TLS clients, so requests impersonate Chrome.
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time
import urllib.parse

from curl_cffi import requests

BASE = "https://shop.myvrn.de"
CLIENT_ID = "vrn"
ORGANIZATION = "vrn"
REDIRECT_URI = "vrn://oauth/callback"
SCOPE = "ticketing offline_access"

# Same content negotiation the SPA's fetch wrapper uses.
JSON_HEADERS = {
    "Content-Type": "application/json;odata.metadata=none;IEEE754Compatible=true;charset=utf-8",
    "Accept": "application/json;odata.metadata=none;IEEE754Compatible=true;charset=utf-8",
}

# TwoFactorProvider enum from the SPA.
MFA_NONE, MFA_AUTHENTICATOR_APP, MFA_EMAIL, MFA_SMS = 0, 1, 2, 3

# ListOrdersV2 bitmasks: TimeFilter{Active, Historic, Future}, TicketFilter{Quota, Abo, Classic, CiBo}.
TIME_ACTIVE, TIME_HISTORIC, TIME_FUTURE = 1, 2, 4
TICKETS_ALL = 1 | 2 | 4 | 8


class VrnError(Exception):
    pass


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _iso(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


class LoginFlow:
    """One interactive login: start() -> [finalize(code)] -> tokens."""

    def __init__(self, impersonate: str = "chrome", timeout: int = 25):
        self.http = requests.Session(impersonate=impersonate)  # keeps the auth cookies
        self.timeout = timeout
        self.verifier = _b64url(secrets.token_bytes(48))
        self.state = secrets.token_urlsafe(16)
        self.return_url: str | None = None

    def _authorize(self):
        challenge = _b64url(hashlib.sha256(self.verifier.encode()).digest())
        q = {
            "client_id": CLIENT_ID, "redirect_uri": REDIRECT_URI, "response_type": "code",
            "scope": SCOPE, "state": self.state, "nonce": secrets.token_urlsafe(16),
            "code_challenge": challenge, "code_challenge_method": "S256",
        }
        r = self.http.get(f"{BASE}/Identity/connect/authorize?" + urllib.parse.urlencode(q),
                          allow_redirects=False, timeout=self.timeout)
        loc = r.headers.get("location", "")
        ret = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query).get("returnUrl", [None])[0]
        if r.status_code != 302 or not ret:
            raise VrnError(f"authorize: unexpected {r.status_code} {loc[:120]}")
        self.return_url = ret

    def start(self, email: str, password: str) -> dict:
        """Steps 1–2. Returns {"next": "done"|"code", ...}; tokens in "tokens" when done."""
        self._authorize()
        r = self.http.post(f"{BASE}/Identity/Authentication/Login", headers=JSON_HEADERS, timeout=self.timeout,
                           data=json.dumps({"Name": email.strip(), "Password": password,
                                            "Client": CLIENT_ID, "Organization": ORGANIZATION,
                                            "RememberLogin": False}))
        res = r.json()
        if not res.get("Succeeded"):
            reason = next((k for k in ("CredentialValidationFailed", "UserNotActivated", "UserLocked",
                                       "InvalidClient", "InvalidOrganization") if res.get(k)), "LoginFailed")
            raise VrnError(reason)
        provider = res.get("TwoFactorProvider") or MFA_NONE
        if provider == MFA_NONE:
            return {"next": "done", "tokens": self._finish()}
        if provider == MFA_EMAIL:
            return {"next": "code", "provider": "email"}
        raise VrnError(f"Unsupported 2FA provider {provider}")

    def finalize(self, code: str) -> dict:
        """Step 3 (email 2FA) then 4–5."""
        r = self.http.post(f"{BASE}/Identity/Authentication/FinalizeLogin", headers=JSON_HEADERS,
                           data=json.dumps(code.strip()), timeout=self.timeout)
        res = r.json() if r.text.strip() else {}
        if r.status_code >= 400 or res.get("Succeeded") is False:
            raise VrnError(f"FinalizeLogin failed: {r.status_code} {r.text[:200]}")
        return {"next": "done", "tokens": self._finish()}

    def _finish(self) -> dict:
        """Steps 4–5: follow the authorize callback to the vrn:// redirect, redeem the code."""
        url = urllib.parse.urljoin(BASE, self.return_url)
        for _ in range(5):  # a hop or two of same-origin redirects before vrn://
            r = self.http.get(url, allow_redirects=False, timeout=self.timeout)
            loc = r.headers.get("location", "")
            if loc.startswith(REDIRECT_URI):
                break
            if r.status_code not in (301, 302, 303, 307) or not loc:
                raise VrnError(f"callback: no vrn:// redirect ({r.status_code})")
            url = urllib.parse.urljoin(url, loc)
        else:
            raise VrnError("callback: too many redirects")
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)
        if q.get("error"):
            raise VrnError(f"authorize error: {q['error'][0]}")
        if q.get("state", [None])[0] != self.state:
            raise VrnError("state mismatch")
        return exchange_code(self.http, q["code"][0], self.verifier, self.timeout)


def exchange_code(http, code: str, verifier: str, timeout: int = 25) -> dict:
    r = http.post(f"{BASE}/Identity/connect/token", timeout=timeout, data={
        "grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT_URI,
        "client_id": CLIENT_ID, "code_verifier": verifier,
    })
    if not r.ok:
        raise VrnError(f"token: {r.status_code} {r.text[:200]}")
    return r.json()


def refresh(refresh_token: str, impersonate: str = "chrome", timeout: int = 25) -> dict:
    r = requests.post(f"{BASE}/Identity/connect/token", impersonate=impersonate, timeout=timeout, data={
        "grant_type": "refresh_token", "refresh_token": refresh_token,
        "client_id": CLIENT_ID, "scope": SCOPE,
    })
    if not r.ok:
        raise VrnError(f"refresh: {r.status_code} {r.text[:200]}")
    new = r.json()
    new.setdefault("refresh_token", refresh_token)
    return new


def _shop(access_token: str, path: str, payload: dict, impersonate="chrome", timeout=25) -> dict:
    r = requests.post(f"{BASE}/TicketShop/{path}", impersonate=impersonate, timeout=timeout,
                      data=json.dumps(payload),
                      headers={"Authorization": "Bearer " + access_token,
                               "Content-Type": "application/json", "Accept": "application/json"})
    if r.status_code == 401:
        raise VrnError("unauthorized")
    return r.json()


def list_orders(access_token: str, include_expired: bool = False, max_number: int = 50) -> list:
    now = time.time()
    res = _shop(access_token, "Shop/ListOrdersV2", {
        "From": _iso(now - 365 * 86400), "To": _iso(now + 400 * 86400),
        "Index": 0, "MaxNumber": max_number,
        "ListOrdersTimeFilterMask": TIME_ACTIVE | TIME_FUTURE | (TIME_HISTORIC if include_expired else 0),
        "ListOrdersTicketFilterMask": TICKETS_ALL,
    })
    if res.get("Error"):
        raise VrnError(res["Error"].get("Message", "ListOrdersV2 failed"))
    return res.get("Orders") or []


def qr_code(access_token: str, ticket_id) -> dict:
    """`ticket_id` is Orders[].Tickets[].Id. Returns {QrCode, TicketName, ValidFrom, ValidUntil, …}."""
    res = _shop(access_token, "Shop/QRCode", {"Id": ticket_id})
    if res.get("Error"):
        raise VrnError(res["Error"].get("Message", "QRCode failed"))
    return res
