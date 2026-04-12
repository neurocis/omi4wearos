# Changelog

## v1.12 (Standalone) — 2026-04-12

### Settings screen, upload log, auth status

**Added**
- **Settings screen**: accessible from the home screen. Shows auth status (✓ credentials set / ✗ not configured), upload log, Setup button, and full credential-retrieval instructions.
- **Upload log**: last 20 upload results stored in SharedPreferences — each entry shows time, file size (KB), session count, and success/failure.
- **`StandaloneOmiApiClient`**: writes a log entry after every upload attempt.
- **SetupScreen**: IP:Port URL shown below the QR code. Instructions note that a computer is easiest.
- **SetupServer HTML + SettingsScreen instructions**: all three credential values (`apiKey`, `accessToken`, `refreshToken`) are found in the same DevTools location — Application → Storage → IndexedDB → `firebaseLocalStorageDb` → `FirebaseLocalStorage` → `firebase:authUser`. No Network tab required.

**Changed**
- HomeScreen bottom link: "Setup" → "Settings"; credential setup is now nested inside the Settings screen.
- Navigation: `settings` route added to `WearApp`; `setup` route navigated from within Settings.

---

## v1.11 (Standalone) — 2026-04-12

### Upload fixes: 401 token refresh + 422 field name

**Fixed**
- **HTTP 401 on upload**: pasted Firebase `idToken` may already be expired when entered via web setup. `tokenExpiresAtSecs` is now stored as `0` when credentials are received from the setup form, forcing a token exchange before the first upload attempt. A 401 response from the API also triggers a forced refresh and one retry.
- **HTTP 422 on upload**: multipart field name was `"audio_file"` — the Omi API requires `"files"`. Fixed to match.

---

## v1.10 (Standalone) — 2026-04-12

### Logo + web setup restored

**Changed**
- Logo updated to `omi4wos-for-black.png` (dark background optimised variant).

**Fixed**
- Web setup (NanoHTTPD QR flow) restored. Credentials can be updated at any time without changing source code.

---

## v1.9 (Standalone) — 2026-04-12

### Setup form: 3-field credential entry

**Changed**
- Setup form replaced email/password with three direct credential fields: Firebase Web API Key, Firebase Token (`accessToken`), Refresh Token — matching the values used in the companion app and obtainable from browser DevTools without a server-side login call.
- `StandaloneOmiConfig.saveFromSetup()` stores all three values; `Credentials.isConfigured` requires `firebaseWebApiKey` and `refreshToken` to be non-blank.

---

## v1.8 (Standalone) — 2026-04-12

### Initial standalone release

First build of the standalone watch app — no phone companion required.

**Architecture**
- Watch records audio, detects speech (Silero VAD), encodes to Opus 16kbps, and uploads directly to `api.omi.me/v2/sync-local-files` over WiFi (or Bluetooth tethering).
- No Data Layer, no phone app, no `AudioReceiverService`.

**Credential setup**
- NanoHTTPD server on port 8080 serves a setup form.
- Watch displays a QR code encoding `http://<wifi-ip>:8080`; user scans on any phone or types the URL into any browser.
- Credentials stored in SharedPreferences; optionally baked in at build time via `local.properties` → `BuildConfig`.

**Upload pipeline**
- OkHttp multipart POST, field name `"files"`, filename `recording_fs320_<timestamp>.bin`.
- Firebase token auto-refresh via `securetoken.googleapis.com/v1/token`.
- 30-second debounce after each speech segment — consecutive segments land in one Omi conversation.
- 5-minute gap detection splits genuinely separate sessions into separate conversations.
- Store-and-forward via `ChunkRepository` — failed uploads stay on disk and are retried.

**VAD / encoding pipeline**
- Silero LSTM (OnnxRuntime 1.14.0 — pinned to avoid armeabi-v7a alignment bug in 1.15+).
- Async Opus encoding on `Dispatchers.IO` (`Channel<EncodeRequest>` + `encoderLoop()`).
- Integer loudness gate, 3s idle classification interval after 30s silence, 3.5s pre-roll buffer.
