# omi4wOS — Standalone Edition

A WearOS watch app that records audio, detects speech with a neural VAD, encodes to Opus, and uploads directly to Omi Cloud — **no phone companion app required**.

<div align="center">
  <img src="omi4wos-for-black.png" width="180" />
</div>

## Quick Install

Download the latest APK from the `releases/` folder in this repository and sideload it onto your WearOS watch via ADB:

```bash
adb install releases/Omi4wOS_Standalone_v1.12.apk
```

No phone app needed. The watch uploads directly to Omi over WiFi (or Bluetooth tethering).

## First-Time Setup

After installing, open the app and tap **Settings → Setup**.

The watch starts a local web server and shows a QR code. Scan it with any phone or type the URL shown below the QR into any browser on the same WiFi network. **A computer is easiest.**

The setup form asks for three values — all found in your browser's developer tools:

### How to get your credentials

1. Open **[app.omi.me](https://app.omi.me)** in Chrome on a computer.
2. Log out, then sign in again with Google to ensure tokens are fresh.
3. Open DevTools (`F12` or `Cmd+Option+I`).
4. Go to the **Application** tab.
5. In the left panel: **Storage → IndexedDB → firebaseLocalStorageDb → FirebaseLocalStorage**.
6. Click the row starting with `firebase:authUser` to expand its value.

From the expanded value:

| Field in setup form | Where to find it |
|---|---|
| **Firebase Web API Key** | `apiKey` field directly in `firebase:authUser` (`AIzaSy…`) |
| **Firebase Token** | `stsTokenManager → accessToken` (`eyJ…`) — expires in ~1h, auto-renewed |
| **Refresh Token** | `stsTokenManager → refreshToken` (`AMf…`) — long-lived |

Paste all three into the setup form and tap **Connect Watch**. The watch stores them and the setup screen shows a confirmation. You can close the browser tab.

Tokens are refreshed automatically before each upload — you only need to go through setup again if you change your Omi account.

## Settings Screen

The Settings screen (accessible from the home screen) shows:

- **Auth status** — green ✓ if credentials are stored, red ✗ if not
- **Upload log** — time, file size, and session count for recent uploads
- **Setup button** — tap to re-run credential setup at any time

## How It Works

```
┌──────────────────────────────────────┐
│            WearOS Watch              │
│                                      │
│  AudioRecord (16kHz PCM16 mono)      │
│       ↓                              │
│  Loudness Gate (52dB RMS pre-filter) │
│       ↓                              │
│  Silero VAD LSTM (OnnxRuntime 1.14)  │
│       ↓                              │
│  Opus Encoder (16kbps, async)        │
│       ↓                              │
│  ChunkRepository (store-and-forward) │
│       ↓                              │
│  30s debounce → upload session       │
│       ↓                              │
│  Omi Cloud /v2/sync-local-files      │
│  (Firebase bearer token, auto-renew) │
└──────────────────────────────────────┘
```

1. **Audio capture** — continuous 16kHz PCM16 mono via `AudioRecord`.
2. **Loudness gate** — each 960ms window is checked against a 52dB RMS threshold before inference runs. Silence is skipped entirely.
3. **Silero VAD** — an LSTM neural network evaluates each window that passes the gate, reporting a speech probability per 32ms frame. Windows with ≥4 frames above 0.5 are flagged as speech.
4. **Opus encoding** — flagged windows are encoded at 16kbps on a dedicated IO coroutine (non-blocking).
5. **Store-and-forward** — chunks are written to the watch's internal filesystem immediately. If the upload fails, they stay on disk and are retried at the next opportunity.
6. **30-second debounce** — after a speech segment ends, the service waits 30 seconds before uploading, so consecutive segments from a continuous conversation land in a single Omi conversation rather than being fragmented.
7. **Session splitting** — segments separated by more than 5 minutes are uploaded as separate Omi conversations.
8. **Token refresh** — if the Firebase token has expired, it is exchanged for a new one using the stored refresh token before the upload proceeds. A 401 response triggers a forced refresh and one retry.

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Wear Material) |
| VAD | Silero LSTM via OnnxRuntime 1.14.0 |
| Audio encoding | Opus via Android MediaCodec 16kbps |
| Networking | OkHttp 4.12 — multipart POST |
| Setup server | NanoHTTPD 2.3.1 |
| QR generation | ZXing Core 3.5.3 |
| Cloud API | Omi `/v2/sync-local-files` |
| Authentication | Firebase token (auto-refresh via `securetoken.googleapis.com`) |

## Build from Source

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17, Android SDK 34
- A WearOS watch (Android 11+)

### Optional: bake credentials into the build

If you want credentials pre-loaded without going through setup, add to `local.properties`:

```
OMI_FIREBASE_WEB_API_KEY=AIzaSy...
OMI_FIREBASE_TOKEN=eyJ...
OMI_FIREBASE_REFRESH_TOKEN=AMf...
```

These are injected as `BuildConfig` fields and seeded into SharedPreferences on first launch. The web setup flow still works and will overwrite them.

### Build

```bash
./gradlew :wear:assembleRelease
# APK: wear/build/outputs/apk/release/wear-release.apk
```

## VAD Selection Rationale

Getting reliable voice activity detection on the Galaxy Watch 7 required working through several approaches. This section documents what was tried and why Silero was ultimately chosen.

### The Problem

The Galaxy Watch 7 runs a 32-bit ARM processor (`armeabi-v7a`). This is a hard constraint that rules out several otherwise viable options.

### Options Evaluated

**WebRTC GMM (original)**
The watch originally used a WebRTC Gaussian Mixture Model — a very fast native C++ classifier from the Chromium telephony stack. It is essentially zero-cost in battery and CPU terms. However it was designed for phone call quality improvement, not ambient monitoring. In practice it generated significant false positives from fan and HVAC noise (broadband, classified as speech), motorcycle and engine sounds (harmonic content in the speech frequency range), and clothing rustle and watch movement.

**Silero via OnnxRuntime (attempted)**
Silero is an LSTM neural network trained specifically on the task of "is this human speech or not?" across thousands of hours of diverse real-world audio. This initially crashed with `SIGBUS BUS_ADRALN` on every launch. The root cause: OnnxRuntime's armeabi-v7a native build uses ARM NEON SIMD instructions that require 16-byte memory alignment, but its protobuf-based model parser performs unaligned reads on internal buffers — a bug present in OnnxRuntime 1.15–1.20 on 32-bit ARM.

**YAMNet TFLite**
TFLite has proper armeabi-v7a support. However it was replaced previously specifically because of battery drain — YAMNet processes audio at 975ms windows with a significantly heavier inference cost than Silero.

### The Fix

OnnxRuntime **1.14.0** does not have the armeabi-v7a alignment bug present in later versions. By pinning to 1.14.0:

```kotlin
implementation("com.github.gkonovalov.android-vad:silero:2.0.7") {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.14.0")
```

The model is extracted from APK assets to `filesDir` on first launch and loaded via file path rather than byte array.

### Comparison

| | WebRTC GMM | YAMNet | Silero |
|---|---|---|---|
| Inference cost | ~2ms | ~150ms | ~120ms |
| False positive rate | High | Low | Very low |
| Engine/motor noise | Fails | Passes | Passes |
| armeabi-v7a support | ✓ | ✓ | ✓ (OnnxRuntime 1.14.0) |
| Purpose-built for VAD | No | No | Yes |

Silero was trained specifically for voice activity detection rather than general audio classification (YAMNet) or telephony noise suppression (WebRTC). This specificity is what allows it to correctly reject motorcycle sounds, fan noise, music, and other environmental audio that defeated heuristic approaches.
