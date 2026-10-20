# myVRN OIDC helper (dev prototype)

A small phone-framed web app that drives the login flow reverse-engineered from
the myVRN APK, so we can build either a fix for the official app or a custom
client on top of it.

It performs the real **OpenID Connect authorization-code + PKCE** flow against
`shop.myvrn.de`, decodes the resulting tokens, and gives you an interactive
panel to probe the ticketing API.

## Why manual callback capture

The app's registered redirect URI is the custom scheme `vrn://oauth/callback`.
A web/loopback redirect (`http://localhost/...`) is **not** registered at the
IdentityServer, so it would reject it. Instead this tool:

1. builds the authorize URL (with a fresh PKCE challenge),
2. you open it in a normal browser and sign in with your **RPTU** account,
3. the browser lands on `vrn://oauth/callback?code=...` (shows an error / blank — expected),
4. you paste that URL back into the app, which exchanges the `code` for tokens.

## Run

```bash
cd webapp
docker compose up --build
```

Then open **http://<this-machine-ip>:8783** on your phone (same Wi-Fi), or
`http://localhost:8783` on this machine.

Stop with `docker compose down`.

### Change the port
Edit the `ports:` line in `docker-compose.yml` (e.g. `"9000:8080"`).
To restrict access to this machine only, use `"127.0.0.1:8783:8080"`.

## The tabs (myVRN design)

The UI follows the original app (colours from `/Layout/ClientTheme`, icons from the APK — see `../pwa/DESIGN.md`):

- **Tickets** — grouped list (Aktuell gültig / Zukünftig gültig / Guthaben / Abgelaufen); tap a ticket for
  the QR code, count-up timer and ticket data.
- **Konto** — browserless login (email + password, email 2FA code) and the profile.
- **Einstellen** — theme (System/Hell/Dunkel) and **Debug-Modus**. Debug mode adds raw API responses,
  token details + refresh, the alternative logins (remote WebView, manual callback paste), the OIDC
  config/discovery and a free-form request probe to the views.

### Previous tabs (now behind debug mode)

- **Login** — generate the link, sign in, paste the callback, get tokens.
- **Tokens** — decoded access/ID-token claims, scope, expiry, and refresh.
- **Tickets** — send authenticated requests to discover/pull the ticket API.
  The real endpoint isn't known from static analysis yet; the probe + the
  discovery button on Config are how we find it (or confirm from one capture).
- **Config** — every OIDC parameter, overridable; fetch the `.well-known`
  discovery document to auto-fill endpoints.

## Confirmed from the APK

| Item | Value |
|---|---|
| Authorize | `https://shop.myvrn.de/Identity/connect/authorize` |
| Token | `https://shop.myvrn.de/Identity/connect/token` |
| client_id | `vrn` (public, no secret) |
| redirect_uri | `vrn://oauth/callback` |
| scope | `ticketing offline_access` |
| PKCE | S256 |

## Server blocks non-browser TLS clients

`shop.myvrn.de` holds HTTPS requests from plain `requests`/`curl` for ~20 s and
then drops the connection without a response — regardless of IP, headers or
HTTP version. Requests that present Chrome's TLS fingerprint are answered
immediately. All outbound calls therefore use `curl_cffi` with
`impersonate="chrome"` (override via `VRN_IMPERSONATE`, e.g. `safari_ios`).

## "Zugriff verweigert" (`/authentication/no-access`)

The login SPA reads the client config from `GET /Layout/ClientConfiguration`
(header `x-sh-tenant: client=VRN,organization=VRN`). For VRN it contains
`Authentication.Public.Login.Access = {Web: false, Mobile: true}`, and the SPA
decides "mobile" from the User-Agent (`isMobile || isTablet`). Desktop browsers
are therefore sent to `no-access`; open the login link on a phone, or use
DevTools device emulation. The URL extras (`appType` etc.) don't affect this —
the server rewrites `appType` to `C5` anyway.

## Tickets & barcode (confirmed)

From the APK's native `net.mentz.ticketing` library:

| Call | Body | Response |
|---|---|---|
| `POST /TicketShop/Shop/ListOrdersV2` | `From`, `To` (ISO 8601), `Index`, `MaxNumber`, `ListOrdersTimeFilterMask` (Active=1, Historic=2, Future=4), `ListOrdersTicketFilterMask` (Quota=1, Abo=2, Classic=4, CiBo=8) | `Orders[]` → `Tickets[]` → `Details` |
| `POST /TicketShop/Shop/QRCode` | `Id` = `Orders[].Tickets[].Id` (not the order id) | `QrCode`, `TicketName`, `ValidFrom`, … |

Both need `Authorization: Bearer <access_token>`.

## Browserless login (protocol for ports)

Implemented in `vrn_client.py`. All requests go to `https://shop.myvrn.de`, keep one
cookie jar for steps 1–4, and use the headers
`Content-Type`/`Accept: application/json;odata.metadata=none;IEEE754Compatible=true;charset=utf-8`.

1. `GET /Identity/connect/authorize?client_id=vrn&redirect_uri=vrn://oauth/callback&response_type=code&scope=ticketing offline_access&state=…&nonce=…&code_challenge=…&code_challenge_method=S256`
   → `302 /authentication/login?…&returnUrl=<callback>` — keep `returnUrl`.
2. `POST /Identity/Authentication/Login` `{"Name": email, "Password": pw, "Client": "vrn", "Organization": "vrn", "RememberLogin": false}`
   → `{"Succeeded", "TwoFactorProvider", "CredentialValidationFailed", "UserLocked", …}` + auth cookie.
   `TwoFactorProvider`: 0 none, 1 authenticator app, 2 email, 3 SMS.
3. If 2 (email): `POST /Identity/Authentication/FinalizeLogin` with body `"123456"` (a JSON string).
4. `GET <returnUrl>` without following redirects → `302 vrn://oauth/callback?code=…&state=…`.
5. `POST /Identity/connect/token` (form) `grant_type=authorization_code&code=…&redirect_uri=vrn://oauth/callback&client_id=vrn&code_verifier=…`.
   Refresh: `grant_type=refresh_token&refresh_token=…&client_id=vrn&scope=…`.
6. Tickets / barcode: see the table below.

Constraints for clients:
- **TLS fingerprint:** the server stalls clients that don't look like a browser
  (Python `requests`, `curl`). Chrome impersonation works; Android's own stack is
  what the official app uses.
- **No CORS:** the server sends no `Access-Control-*` headers and the login relies
  on cookies + a non-HTTP redirect, so a browser/PWA cannot call it directly — it
  needs a proxy (this app). A native Android app can call it directly.

## PWA & offline

- Installable (`/manifest.webmanifest`, icons in `static/icons/icon-*.png`).
- `static/sw.js` (served at `/sw.js`) caches only the app shell + `/offline`; server-rendered
  pages are never cached (personal data). A failed navigation shows `/offline`.
- When online, the ticket list/detail pages embed a snapshot of the valid tickets incl. QR
  code (`offline_snapshot()`, QR prefetched for up to `OFFLINE_MAX_TICKETS`), which
  `static/ticket.js` stores in `localStorage` (`vrnOffline.v1`). `/offline` renders from it
  and hides tickets whose end date has passed.
- Cleared on logout, when "Tickets offline speichern" is switched off, or via
  "Offline-Daten löschen" in Settings.
- After changing shell assets, bump `VERSION` in `sw.js` so clients re-cache `/offline`.

## Persistent login ("Angemeldet bleiben")

`remember.py`. A device cookie `device=<id>.<secret>` (HttpOnly, Secure, 90 days) maps to a
record in `data/remember.json` (Docker volume `./data:/data`, mode 600): the VRN refresh
token, AES-GCM encrypted with a key derived from the *cookie secret* (HKDF, salted with
`FLASK_SECRET`), plus a fingerprint of `ACCESS_PASSWORD`.

- New session + valid device cookie → gate passed and tokens refreshed from the stored
  refresh token (re-encrypted on every refresh, VRN rotates it).
- Changing `ACCESS_PASSWORD` (or `FLASK_SECRET`) invalidates every remembered device.
- Logout deletes the stored token (the gate stays remembered when an access password is set).
- If VRN revokes/expires the refresh token, the user simply has to log in again.

## Security (public deployment)

Published through the Cloudflare tunnel as `vrn.heineke.cc` → `localhost:8783`
(the container only listens on 127.0.0.1). Hardening lives in `security.py`:

- **Per-user isolation:** every browser gets its own server-side session (random
  256-bit id in an `HttpOnly; Secure; SameSite=Lax` cookie); tokens never leave the
  server. The id is rotated on login, sessions expire after 12 h idle / 7 days, at
  most 500 are kept, and logout deletes the session.
- **PUBLIC_MODE=1** (default): the request probe and OIDC config editor (SSRF) and
  the remote WebView/noVNC (one browser shared by everyone) are disabled (404).
- **CSRF** token on every POST; **rate limits** on login (10/IP, 5/account per 15 min)
  and the gate; **headers**: CSP (self only, bwip-js is vendored), HSTS,
  `X-Frame-Options: DENY`, `no-store` for pages.
- **ACCESS_PASSWORD** (optional, in `.env`): a password gate in front of the whole app.
- Secrets live in `.env` (`FLASK_SECRET`, `ACCESS_PASSWORD`), mode 600, git/docker-ignored.
- Tokens are kept in memory only (gone on restart).

LAN development: set `PUBLIC_MODE=0` and `COOKIE_SECURE=0`, publish `8783:8080`
(and `8784:6080` for the remote WebView).

## Not internet-tested here

The analysis machine has no outbound network, so the live token exchange and
ticket probe can't be exercised until you run this where it can reach
`shop.myvrn.de` (your Pi/desktop). The app surfaces the real HTTP status/body
of every call so you can iterate.
