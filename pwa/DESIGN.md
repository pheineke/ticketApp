# myVRN Lite: design plan

A slimmed-down client (PWA + WebView wrapper for older Androids) that looks like the
original myVRN app and covers what you need day to day: **login, my tickets, ticket QR
code, account**. Buying tickets, route planning and the map are out of scope.

Mockup: `mockup.html` (open in a browser; icons come from `assets/icons/`).

> Logo, icons and colours are taken from the APK / VRN server and are VRN's (and
> Mentz's) property. Use them privately only; don't publish or distribute them.

---

## 1. Sources

| What | Where it comes from |
|---|---|
| Colours (light/dark) | `GET /Layout/ClientTheme?Mode=0/1` (VRN theme tokens) + dominant colours of `app_icon.png` / `gullivr_splash.png` |
| Font | No custom font in the APK (only `MaterialIcons`, `CupertinoIcons`) → system font: Roboto on Android |
| Icons | `flutter_assets/assets/images/svgs/*`, `packages/gullivr_ui/assets/images/svgs/*` |
| Texts & navigation | `assets/resources/lang/de.json`, `packages/gullivr_ui/.../de.json` |
| Screen structure | Widget names in `libapp.so` (`MyTicketsTabView`, `GullivrTicketCardView`, `TicketDetailCountUpView`, `CurrentPurchasedMyTicketView`, `QRCodeView`, `GullivrSidebarButton` …) |

## 2. Design tokens

```css
:root {
  --vrn-blue:        #0098d8;  /* colorPrimary, links, active tab, buttons */
  --vrn-navy:        #002d59;  /* "VRN" lettering, headings, splash gradient end, navigator icons (#002f5a) */
  --bg:              #f4f4f4;  /* bodyBg */
  --surface:         #ffffff;  /* cards, app bar, bottom nav */
  --text:            #414141;  /* colorText */
  --text-heading:    rgba(0,0,0,.85);
  --text-muted:      #767676;  /* colorTextPlaceholder / borderColor */
  --divider:         rgba(5,5,5,.06);
  --hover:           #eeeeee;  /* itemActiveBg */
  --success:         #52c41a;  /* "Gültig" */
  --warning:         #fa8c16;
  --error:           #f5222d;
  --dticket-yellow:  #fcbe00;  /* accent in the Deutschlandticket logo */
  --radius:          4px;      /* borderRadius */
  --pad-block:       20px;     /* paddinBlock */
  --font: Roboto, -apple-system, "Segoe UI", "Noto Sans", Arial, sans-serif;
  --fs-sm: 12px; --fs: 14px; --fs-lg: 16px; --fs-h: 20px;
}
/* Dark (ClientTheme Mode=1) */
:root[data-theme=dark] {
  --bg: #272727; --surface: #414141; --text: #ffffff; --text-heading: #ffffff;
  --text-muted: #aaaaaa; --hover: #4a4a4a; --error: #f52a2a;
}
```

- **Splash:** vertical gradient `#0098d8 → #002d59`, white logo (`vrn_logo_dark.svg`) centred.
- **Icons:** line icons in 24 px; tint them with CSS `mask-image` + `background: currentColor` so dark mode works.
- **Elevation:** flat, as in the original. Cards are white on `--bg` with a hairline border (`--divider`) and at most a light shadow.

## 3. App shell

Gullivr layout: **app bar on top, bottom tab bar, sidebar/"Home navigator" for everything else.**

- **App bar** (56 px, `--surface`): ☰ on the left (opens the navigator), page title in the middle/left
  (`--fs-h`, `--vrn-navy`), contextual action on the right (e.g. refresh).
- **Bottom navigation** (56 px, `--surface`, top border `--divider`): the original lets you configure
  2–5 tabs (`customised_bottom_nav_bar_*`); VRN default labels are *Abfahrt · Ticket-Shop · Konto · Meldung · Einstellen*.
  **Lite:** 3 tabs, **Tickets · Konto · Einstellen**. Active tab is `--vrn-blue` (icon + label), inactive is `--text-muted`.
- **Home navigator** (sidebar): list rows with a 36 px round navy icon + title + subtitle,
  e.g. *Meine Tickets: "Meine aktuell gültigen Tickets."*, *VRN Konto: "Meine Benutzerdaten und Login."*,
  plus Hilfe & Kontakt, Datenschutz & AGB, Impressum (external links).

## 4. Screens

### 4.1 Splash → Login
- Splash for at most 1 s (only while tokens load from storage).
- **Login** (`login`, `login_identity_provider_page`): VRN logo at the top, fields *E-Mail*, *Passwort*
  (with an eye toggle as on the web login), primary button **Einloggen** (full width, `--vrn-blue`, white text, radius 4).
  Below it a secondary link *Passwort vergessen?* (external).
- **Email code** (2FA, `TwoFactorProvider = 2`): a single numeric field (`autocomplete=one-time-code`),
  hint "Wir haben dir einen Code an *mail* geschickt", button **Bestätigen**, link *Code erneut senden*.
- Later: a **Hochschul-Login** button (`login_deutschland_ticket_student`), i.e. the Shibboleth path of the original.

### 4.2 My tickets (start tab)
- Title *Tickets*. Groups as in `MyTicketsGroupType`, each with a section header in `--fs-sm`, uppercase, `--text-muted`:
  **Aktuell gültig** · **Zukünftig gültig** · **Guthaben** · **Abgelaufen** (collapsed).
- **Ticket card** (`GullivrTicketCardView`): white, radius 4.
  - Left: product logo (Deutschlandticket SVG, otherwise `ticket.svg` in a blue circle).
  - Middle: **TicketName** (`--fs-lg`, semibold), below it *Gültig bis 31.10.2026, 23:59* (`--text-muted`).
  - Right: status chip **Gültig** (green) / *ab 01.11.* (blue) / *Abgelaufen* (grey) + chevron.
  - Tap opens the ticket detail.
- Empty state: text from `my_tickets_empty` ("Hier werden dir Tickets angezeigt, die du gekauft hast …").
- Pull to refresh; offline banner if there's no network ("Offline · zuletzt aktualisiert 14:32").

### 4.3 Ticket detail with QR code (the most important screen)
- Full screen, white background **even in dark mode** (scannability), **max brightness + wake lock**.
- From top to bottom:
  1. Ticket header in `--vrn-blue` with white text: TicketName, below it the price level / area (`ticket_validity_wabentarif`).
  2. **QR / Aztec code**, as large as possible (≈ 80 % of the width), quiet zone ≥ 4 modules, label `qr_code_label`.
  3. **Count-up timer** (`TicketDetailCountUpView`): seconds counting up since opening, so inspectors
     can tell a live ticket from a screenshot. Small, monospace, with an info icon → bottom sheet
     (`ticket_count_up_bottom_sheet_*`).
  4. Data table, label left (`--text-muted`) / value right:
     *Ticketinhaber* (Holder), *Geburtsdatum* (Dob), *Gültig ab*, *Gültig bis*, *Gültig für* (AreaName / TariffArea),
     *Ticketnummer* (AuthorizationNumber / OrderNumber).
- Error state: `no_qr_code_available`.

### 4.4 Account
- Card "*Eingeloggt als:* name@mail" (`profile_logged_in_as`), below it Name, E-Mail, Geburtsdatum
  (`/Identity/OData/UserProfile?$expand=User(...)`).
- Buttons: **Abmelden** (outlined), *Login Daten ändern* (external).

### 4.5 Settings
- Design: System / Hell / Dunkel.
- Language: Deutsch / English.
- Keep tickets offline (on/off), *Daten löschen*.

## 5. Mapping API → UI

| UI | Source |
|---|---|
| Ticket list, groups | `POST /TicketShop/Shop/ListOrdersV2` → `Orders[].Tickets[]`; group by `ValidFrom`/`ValidTo` vs. now (`Canceled`/`Refunded` → hide) |
| Card title / validity | `Tickets[].TicketName`, `ValidFrom`, `ValidTo` |
| QR code | `POST /TicketShop/Shop/QRCode {Id: Tickets[].Id}` → `QrCode` (+ `TicketName`, `PriceLevel`, `ValidFrom`, `ValidUntil`, `AreaNumber`) |
| Holder, date of birth, area | `Tickets[].Details` (`Holder`, `Dob`, `AreaName`, `TariffArea`, `AuthorizationNumber`) |
| Account | `GET /Identity/OData/UserProfile?$expand=User($select=Name),User($expand=UserProperty)` |

## 6. Technical constraints (old Androids)

- **No framework**, vanilla JS (ES2017) + CSS custom properties; target Chrome/WebView ≥ 61
  (Android 5+ with an updated WebView). No build step needed.
- **Offline first:** store tickets + the last QR code per ticket in IndexedDB/localStorage,
  so it works during a ticket check without signal. A service worker caches the shell.
- **Proxy is required:** VRN sends no CORS headers and checks the TLS fingerprint →
  the PWA talks to its own backend (the Flask app's `vrn_client.py` as a JSON API), not to VRN directly.
- **Wrapper for old devices:** a minimal Android WebView app (minSdk 21) that loads the PWA;
  a Trusted Web Activity (TWA) would need Chrome ≥ 72.
- QR rendering on the client (e.g. a small QR/Aztec library), because `QrCode` is data, not an image.
- Brightness: Screen Wake Lock API where available; in the WebView wrapper via a JS bridge
  (`WindowManager.LayoutParams.screenBrightness = 1f`).
