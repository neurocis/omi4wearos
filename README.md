# omi4wOS — cipioh Direct Cloud Sync Edition

A multi-module Android/Kotlin project that transforms your WearOS watch into an incredibly efficient, natively integrated Omi speech companion. 

Adapted the original version which performed Android transcription, **this cipioh-edition rewrite fundamentally redesigns the architectural flow to precisely emulate the Omi device.** The watch now generates native 16kbps Opus chunks, seamlessly streams them to the phone companion, and directly constructs Omi-compatible `.bin` archives that are pushed natively into Omi's `/v2/sync-local-files` cloud API for state-of-the-art server-side transcription and intelligence.

<div align="center">
  <img src="https://github.com/user-attachments/assets/e8de0170-4512-4a55-bc84-e5f4a6d9b833" width="250" />
  <img src="https://github.com/user-attachments/assets/096cb612-54f4-4a26-b7c9-17a58bdb2d81" width="250" />
  <img src="https://github.com/user-attachments/assets/825d95c0-ba44-47fa-b80b-8b0d1220ca15" width="250" />
</div>

## Quick Install (Pre-compiled Releases)
If you do not want to compile the code in Android Studio, you can natively download the fully packaged APK files directly from the `/releases` folder in this repository! 

The phone companion APK is required for both watch builds. Pick the APKs that match your needs:

| File | Description |
|---|---|
| `Omi4wOS_Mobile_v1.4.apk` | **Recommended phone build.** Realtime/Batch stream mode toggle, clock-aligned sync intervals, configurable batch interval. Install onto your **Android Phone**. |
| `Omi4wOS_Wear_v1.4.apk` | **Recommended watch build.** Clock-aligned sync, START_STICKY crash fix, respects stream mode from phone. Install via ADB onto your **Watch**. |
| `Omi4wOS_Mobile_v1.3.apk` | Phone build with Realtime/Batch toggle and configurable interval (no clock-aligned options). |
| `Omi4wOS_Wear_v1.3.apk` | Watch build with stream mode support (no clock-aligned sync, no crash fix). |
| `Omi4wOS_Mobile_v1.2.apk` | Phone build with upload tracking + retry, live watch status. |
| `Omi4wOS_Wear_v1.2_Silero.apk` | Watch build with Silero LSTM VAD, force-sync/start/stop support. |
| `Omi4wOS_Wear_v1.1_Silero.apk` | Silero VAD with 1.5s pre-roll (sometimes clips first words). |
| `Omi4wOS_Wear_v1.0.apk` | Original watch build with WebRTC GMM VAD. Smaller (30MB) but higher false positive rate. |
| `Omi4wOS_Mobile_v1.0.apk` | Original phone build. Works but lacks upload history and status UI. |

## Architecture

```
┌──────────────────────────────────────┐
│            WearOS Watch              │
│                                      │
│  AudioRecord  (16kHz PCM16 mono)     │
│       ↓                              │
│  Loudness Gate (52dB RMS pre-filter) │
│       ↓                              │
│  Silero VAD LSTM (OnnxRuntime 1.14)  │
│       ↓                              │
│  Linear Buffer + Opus Encoder API    │
│       ↓                              │
│  BLE Batch Blast (on phrase end)     │
└──────────────────────────────────────┘
              ↓  BLE/WiFi (Native Opus Streams)
┌──────────────────────────────────────┐
│          Phone Companion             │
│                                      │
│  WearableListener / Direct Receivers │
│       ↓                              │
│  Construct Omi (.bin) archive        │
│       ↓                              │
│  Omi Cloud Core (/sync-local-files)  │
│       ↓                              │
│  Local SQLite Cache (Upload History) │
└──────────────────────────────────────┘
```

## Modules

| Module    | Description                                      |
|-----------|--------------------------------------------------|
| `:shared` | Common data models, constants, Data Layer paths  |
| `:wear`   | WearOS watch app — native AudioRecord, Silero LSTM VAD, and batch Opus compression |
| `:mobile` | Phone companion — Bluetooth aggregator, `.bin` compiler, and Firebase token auto-refresh |

## Tech Stack

| Component         | Technology                              |
|-------------------|-----------------------------------------|
| Language          | Kotlin                                  |
| UI (Watch/Phone)  | Jetpack Compose + Material3             |
| VAD               | Silero LSTM via OnnxRuntime 1.14.0      |
| Audio Encoding    | Opus via Android MediaCodec 16kbps      |
| Cloud Upload      | Multipart POST v2/sync-local-files      |
| Authentication    | Omi Firebase IndexedDB tokens           |

## Prerequisites

- Android Studio Hedgehog (2023.1.1)+
- JDK 17, Android SDK 34
- A WearOS watch (Android 11+)
- An Android phone (Android 9+)

## Configuration & Cloud Setup

To interface perfectly with Omi Cloud natively, this system securely routes through Omi's Firebase Backend. You have to provide your device with your session tokens extracted from your browser.

1. Open exactly **[app.omi.me](https://app.omi.me)** in Chrome and sign in to your dashboard.
2. Open Chrome Developer Tools (`F12` or `Cmd+Option+I`)
3. **Grab the ID Token:** Navigate to the `Network` tab, reload the page, click any request, look at the Request Headers, and copy the long string after `Authorization: Bearer `.
4. **Grab the Refresh Token & API Key:** Navigate to the `Application` tab. Open `IndexedDB` → `firebaseLocalStorageDb`. Copy the `refreshToken` from your user block. Also grab the `AIza...` Web API key from any network request URL parameter.
5. Paste these into your **omi4wOS Companion Settings UI**. The app will natively auto-renew your token forever!

## How It Works

1. **Watch**: Continuously monitors audio by routing microphone polling buffers into the DSP every `960ms`, ensuring the OS CPU can sleep between reads.
2. **Loudness Gate**: Each 960ms window is checked against a 52dB RMS threshold before any inference runs. Windows below the threshold are skipped entirely, keeping the CPU idle during silence.
3. **Silero VAD**: An LSTM neural network (Silero) evaluates each window that passes the loudness gate. It classifies 30 × 32ms frames per window and reports a speech probability per frame. Windows with ≥4 frames above 0.5 probability are flagged as speech.
4. **Local Caching**: Audio containing voice activity is Opus-encoded and immediately serialized to the watch's internal filesystem. This ensures no data is lost if the watch is away from the phone.
5. **Data Transmission**: Once a sentence concludes, the watch evaluates Bluetooth connectivity. In **Realtime Stream** mode, the watch immediately transmits the completed segment as soon as it ends. In **Batch Stream** mode, the watch accumulates segments and syncs on a schedule you set from the phone companion. Available intervals: `:00` (once per hour, at the top of the hour), `:30` (twice per hour, at :00 and :30), or fixed durations of 5, 10, 15, 30, 60, 90, or 120 minutes. The `:00` and `:30` options are clock-aligned — the watch checks whether the most recently passed boundary (:00 or :30) has not yet been synced, so syncs happen at predictable wall-clock times regardless of when the app started. Fixed-duration intervals count down from the last sync. If disconnected, files are securely retained until a background worker syncs them upon reconnection.
6. **Phone**: The companion app listens on the Data Layer, assembling the received Opus payloads into an `.bin` archive.
7. **Cloud Upload**: The phone pushes the compiled `.bin` archive into the Omi Cloud using standard Firebase Authentication tokens.

## What's New in v1.4

- **Clock-Aligned Sync Intervals**: Two new batch interval options — `:00` and `:30`. `:00` syncs once per hour at the top of the hour. `:30` syncs twice per hour, at :00 and :30. Both compare against the most recently passed clock boundary rather than counting down from last sync, so uploads happen at predictable wall-clock times no matter when the app started.
- **Watch Service Crash Fix**: Fixed an overnight crash caused by a `START_STICKY` null-intent restart. When Android's battery optimizer kills the foreground service and the OS restarts it, `onStartCommand` receives a null intent. Previously this fell through without calling `startForeground()`, causing an immediate re-kill. Now the watch reads a persisted `recording_enabled` flag from SharedPreferences on restart — if recording was active when killed, it resumes automatically; otherwise it shuts down cleanly.
- **Realtime Stream Mode** *(introduced in v1.3)*: Uploads each completed speech segment to Omi immediately after it ends. Toggle between Realtime and Batch from the phone companion UI.
- **Configurable Batch Interval** *(introduced in v1.3)*: Choose from `:00`, `:30`, 5, 10, 15, 30, 60, 90, or 120 minutes. Default is 60 minutes. Setting is persisted and pushed to the watch automatically.
- **Force Sync removed in Realtime Mode** *(introduced in v1.3)*: Replaced with a "● Realtime Mode" indicator when per-segment syncing is active.

## Key Upgrades from Original Base

- **Direct Cloud Integration:** Removed Android SpeechRecognizer, assembling standard Limitless-compatible `.bin` archives that upload directly to the `/v2/sync-local-files` endpoint.
- **Silero LSTM VAD:** Replaced the TFLite YAMNet classifier with Silero, a purpose-built voice activity detection LSTM trained specifically to distinguish human speech from environmental noise. See [VAD Selection Rationale](#vad-selection-rationale) below.
- **BLE Batch Transfer:** Changed the transmission behavior from streaming every second to batch-transmitting the completed payload at the end of a phrase to reduce radio activity.
- **Store-and-Forward Caching Engine:** Includes a local disk-caching mechanism (`ChunkRepository`). If Bluetooth connection drops, the watch saves up to 500MB of Opus audio to disk. A background worker syncs the missed files chronologically upon reconnection.
- **Duplicate Audio Prevention**: Companion dual-listeners track immutable Chunk Index IDs to drop stuttering or duplicated chunks in bad connections.
- **Dynamic Conversational Hysteresis**: Switches detection sensitivity between Idle Mode (2 consecutive positive windows required) and Active Conversation Mode (1 window sufficient), preventing false positives during silence while remaining responsive mid-conversation.
- **Pre-roll Buffer**: Retains 1.5s of audio prior to speech onset to prevent sentence cut-off when transitioning from idle, without the encoding overhead of longer buffers.
- **Idle Power Throttling**: Classification interval doubles from 960ms to 1920ms after 5 minutes of silence. Connectivity polling reduced from 30s to 2 minutes.
- **Background Upload Retry**: Failed `.bin` uploads are cached natively in a local Room database and re-attempted on next internet connection.

---

## VAD Selection Rationale

Getting reliable voice activity detection on the Galaxy Watch 7 required working through several approaches. This section documents what was tried and why Silero was ultimately chosen.

### The Problem

The Galaxy Watch 7 runs a 32-bit ARM processor (`armeabi-v7a`). This is a hard constraint that rules out several otherwise viable options.

### Options Evaluated

**WebRTC GMM (original)**
The watch originally used a WebRTC Gaussian Mixture Model — a very fast native C++ classifier from the Chromium telephony stack. It is essentially zero-cost in battery and CPU terms. However it was designed for phone call quality improvement, not ambient monitoring. In practice it generated significant false positives from:
- Fan and HVAC noise (broadband, classified as speech)
- Motorcycle and engine sounds (harmonic content in the speech frequency range)
- Clothing rustle and watch movement

Tuning attempts (raising thresholds, adding energy variance checks, adding pitch detection and pitch stability gates) progressively reduced false positives but could not fully solve the problem without also missing real speech.

**Silero via OnnxRuntime (attempted)**
Silero is an LSTM neural network trained specifically on the task of "is this human speech or not?" across thousands of hours of diverse real-world audio. The `gkonovalov/android-vad` library bundles the Silero ONNX model with OnnxRuntime as its inference backend.

This initially crashed with `SIGBUS BUS_ADRALN` (bus error, unaligned memory access) on every launch. The root cause: OnnxRuntime's armeabi-v7a native build uses ARM NEON SIMD instructions that require 16-byte memory alignment, but its protobuf-based model parser performs unaligned reads on internal buffers — a bug present in OnnxRuntime 1.15–1.20 on 32-bit ARM. Loading the model via file path (mmap) rather than byte array was attempted but the crash site was in the parser itself, not the loader.

**Silero TFLite**
The Silero team does not provide a TFLite export and has stated they are not willing to produce one. This path is unavailable without converting the model yourself offline (ONNX → TF SavedModel → TFLite), which is non-trivial and may produce a broken graph due to LSTM op compatibility issues.

**YAMNet TFLite**
YAMNet is a TFLite-backed audio event classifier originally used in this project. TFLite has proper armeabi-v7a support. However it was replaced previously specifically because of battery drain — YAMNet processes audio at 975ms windows with a significantly heavier inference cost than Silero, and the original implementation ran it on every frame continuously rather than gating it.

### The Fix

OnnxRuntime **1.14.0** does not have the armeabi-v7a alignment bug present in later versions. By pinning to 1.14.0 (and excluding the version pulled in transitively by the gkonovalov silero library), Silero initializes and runs inference correctly on the Galaxy Watch 7.

```kotlin
implementation("com.github.gkonovalov.android-vad:silero:2.0.7") {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.14.0")
```

The model is also extracted from APK assets to `filesDir` on first launch and loaded via file path rather than byte array, keeping memory access patterns as safe as possible.

### Why Silero Over the Alternatives

| | WebRTC GMM | YAMNet | Silero |
|---|---|---|---|
| Inference cost | ~2ms | ~150ms | ~120ms |
| False positive rate | High | Low | Very low |
| Engine/motor noise | Fails | Passes | Passes |
| armeabi-v7a support | ✓ | ✓ | ✓ (OnnxRuntime 1.14.0) |
| Purpose-built for VAD | No | No | Yes |

Silero was trained specifically for voice activity detection rather than general audio classification (YAMNet) or telephony noise suppression (WebRTC). This specificity is what allows it to correctly reject motorcycle sounds, fan noise, music, and other environmental audio that defeated heuristic approaches.
