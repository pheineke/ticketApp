# myVRN Lite for Android

A native Android client for myVRN tickets that runs on **Android 5.0 (API 21) and newer**.
Same scope and design as the web client (`../webapp`, design plan in `../pwa/DESIGN.md`):
login, my tickets, the ticket barcode, account and settings. No server or proxy is needed.
The app talks to `shop.myvrn.de` directly, using the protocol documented in
`../webapp/vrn_client.py`.

> Logo, icons and colours are VRN's (and Mentz's) property. Build the app for private use
> only; don't publish or distribute the APK.

## Build and install

Requirements: JDK 17+ and the Android SDK with platform 36 (`local.properties` →
`sdk.dir=…`, or `ANDROID_HOME`).

```bash
cd android
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug   # JVM tests + lint (NewApi guards Android 5)
```

For a smaller, minified release build, create `android/keystore.properties` (git-ignored)
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run
`./gradlew assembleRelease`. Without that file the release APK is unsigned.

To offer the APK in the web client's **App** tab, copy it to `../webapp/downloads/`
(e.g. as `myVRN-Lite-1.0.apk`). Keep signing with the same key, or phones can't update over
the old version.

## What it does

- **Konto**: e-mail + password login with the e-mail code (2FA) step, "Angemeldet bleiben",
  and the profile (falls back to `Payment/Customer/PaymentInfos`, as the web client does).
- **Tickets**: `ListOrdersV2` grouped into *Aktuell gültig*, *Zukünftig gültig* and *Guthaben*.
  Expired and undated tickets only show in debug mode. Pull the latest with ↻.
- **Ticket detail**: the `Shop/QRCode` payload drawn as an Aztec code on white (base64 →
  bytes, 23 % error correction, like bwip-js in the web client). Screen at full brightness and
  kept on, count-up timer, tap the code to spin it.
- **Offline**: the tickets and the barcodes of up to 6 valid tickets are kept on the device, so
  a ticket check works without signal. The copy is cleared on logout or in the settings.
- **Einstellen**: System/Hell/Dunkel, "myVRN Lite" red or "VRN Original" blue, offline copy,
  debug mode (raw API responses, token details, barcode symbology).

## Android 5 notes

- **No AndroidX or Compose.** Current AndroidX and Compose releases have moved past API 21,
  so the UI uses framework views and `Activity`. Light/dark is a `uiMode` override in
  `BaseActivity`, and the colour schemes are two themes with palette attributes (`res/values/attrs.xml`).
- **TLS trust.** `shop.myvrn.de` uses a Let's Encrypt chain that ends at *ISRG Root X1*, which
  Android only trusts from 7.1.1. Without help, every request fails on Android 5–7.0. `net/Tls.kt`
  trusts the system CAs plus the ISRG roots bundled in `res/raw/` (SHA-256 fingerprints
  `96:BC:EC:06…08:C6` for X1 and `69:72:9B:8E…14:70` for X2). The server accepts TLS 1.2 with
  ECDHE-RSA-AES-GCM, which Android 5 supports.
- **HTTP**: OkHttp 4.12, the last line that supports API 21 without AndroidX. Barcodes use
  ZXing core, which needs nothing newer than API 19.
- **Storage**: tokens and the offline copy are AES-GCM encrypted with an Android Keystore key
  on Android 6+. Android 5's Keystore has no AES keys, so there they're stored unencrypted, but
  still private to the app. Backups and device transfer are off on all versions.
- **New Androids** are covered too: edge-to-edge insets (Android 15+), predictive back
  (`OnBackInvokedCallback`, 13+) and themed icons.

## Layout

```
app/src/main/kotlin/de/heineke/myvrnlite/
  net/VrnClient.kt   login flow (PKCE, e-mail code), token refresh, ListOrdersV2, QRCode, profile
  net/Tls.kt         system + bundled ISRG roots
  net/Tokens.kt      token response, JWT claims, profile model
  data/Tickets.kt    date parsing and ticket grouping (port of app.py's ticket_view/grouped_tickets)
  data/Repo.kt       app state, background calls, token refresh, offline copy
  data/Store.kt      settings, Keystore encryption, sealed files
  ui/                MainActivity (tabs + drawer), TicketsPage, AccountPage, SettingsPage,
                     TicketActivity, Barcode
app/src/test/        date/grouping tests and an Aztec/QR round trip through ZXing's reader
```
