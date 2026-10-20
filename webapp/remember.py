"""
Persistent login ("Angemeldet bleiben") that survives server restarts.

Each remembered device has a cookie `device=<id>.<secret>`. The server keeps, per id:
  - sha256(secret)                  to verify the cookie,
  - the VRN refresh token, AES-GCM encrypted with a key derived from the *cookie secret*
    (HKDF, salted with FLASK_SECRET) — the store alone can't be decrypted,
  - a fingerprint of ACCESS_PASSWORD: change the password and every remembered device is
    invalid (they have to pass the gate and log in again).
Records expire after REMEMBER_DAYS without use. The refresh token is re-encrypted
whenever VRN rotates it.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

STORE_PATH = os.environ.get("REMEMBER_STORE", "/data/remember.json")
REMEMBER_DAYS = int(os.environ.get("REMEMBER_DAYS", "90"))
MAX_AGE = REMEMBER_DAYS * 86400
COOKIE = "device"

_lock = threading.Lock()


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _server_secret() -> bytes:
    return (os.environ.get("FLASK_SECRET") or "dev-only-secret").encode()


def gate_fingerprint() -> str:
    """Binds records to the current ACCESS_PASSWORD (and server secret)."""
    pw = os.environ.get("ACCESS_PASSWORD", "")
    return hmac.new(_server_secret(), b"gate:" + pw.encode(), hashlib.sha256).hexdigest()


def _key(secret: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=_server_secret(),
                info=b"myvrn-lite remember v1").derive(secret)


def _load() -> dict:
    try:
        with open(STORE_PATH) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save(data: dict):
    os.makedirs(os.path.dirname(STORE_PATH), exist_ok=True)
    tmp = STORE_PATH + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f)
    os.replace(tmp, STORE_PATH)


def _prune(data: dict, now: float) -> dict:
    return {k: v for k, v in data.items() if now - v.get("last_used", 0) < MAX_AGE}


def _parse(cookie: str | None) -> tuple[str, bytes] | None:
    if not cookie or "." not in cookie:
        return None
    rid, sec = cookie.split(".", 1)
    try:
        return rid, _unb64(sec)
    except Exception:
        return None


def _record(cookie: str | None, data: dict) -> tuple[str, bytes, dict] | None:
    parsed = _parse(cookie)
    if not parsed:
        return None
    rid, sec = parsed
    rec = data.get(rid)
    if not rec or not hmac.compare_digest(rec.get("hash", ""), hashlib.sha256(sec).hexdigest()):
        return None
    return rid, sec, rec


def create(refresh_token: str | None = None) -> str:
    """New remembered device; returns the cookie value."""
    rid, sec = secrets.token_urlsafe(12), secrets.token_bytes(32)
    now = time.time()
    with _lock:
        data = _prune(_load(), now)
        data[rid] = {"hash": hashlib.sha256(sec).hexdigest(), "gate": gate_fingerprint(),
                     "created": now, "last_used": now, "rt": None}
        if refresh_token:
            data[rid]["rt"] = _encrypt(sec, refresh_token)
        _save(data)
    return f"{rid}.{_b64(sec)}"


def _encrypt(sec: bytes, plaintext: str) -> str:
    nonce = secrets.token_bytes(12)
    return _b64(nonce + AESGCM(_key(sec)).encrypt(nonce, plaintext.encode(), None))


def _decrypt(sec: bytes, blob: str) -> str | None:
    try:
        raw = _unb64(blob)
        return AESGCM(_key(sec)).decrypt(raw[:12], raw[12:], None).decode()
    except Exception:
        return None


def restore(cookie: str | None) -> dict | None:
    """Valid record for this cookie -> {"gate": True, "refresh_token": str | None}; else None.

    Records from before an ACCESS_PASSWORD change are deleted."""
    now = time.time()
    with _lock:
        data = _load()
        found = _record(cookie, data)
        if not found:
            return None
        rid, sec, rec = found
        if not hmac.compare_digest(rec.get("gate", ""), gate_fingerprint()):
            del data[rid]
            _save(data)
            return None
        rec["last_used"] = now
        _save(data)
    rt = _decrypt(sec, rec["rt"]) if rec.get("rt") else None
    return {"gate": True, "refresh_token": rt}


def update_token(cookie: str | None, refresh_token: str | None) -> bool:
    """Store (or with None: forget) the device's refresh token. False if the cookie is invalid."""
    with _lock:
        data = _load()
        found = _record(cookie, data)
        if not found:
            return False
        rid, sec, rec = found
        rec["rt"] = _encrypt(sec, refresh_token) if refresh_token else None
        rec["last_used"] = time.time()
        _save(data)
    return True


def forget(cookie: str | None):
    """Delete the device record entirely."""
    with _lock:
        data = _load()
        found = _record(cookie, data)
        if found:
            del data[found[0]]
            _save(data)


def stats() -> dict:
    data = _load()
    return {"devices": len(data), "with_login": sum(1 for v in data.values() if v.get("rt"))}
