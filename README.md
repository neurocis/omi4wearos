# omi4wearOS

A WearOS app that transforms your smartwatch into a seamless [Omi](https://www.omi.me/) speech companion — capturing, classifying, and streaming speech directly into the Omi ecosystem.

<div align="center">
  <img src="https://github.com/user-attachments/assets/825d95c0-ba44-47fa-b80b-8b0d1220ca15" width="250" />
</div>

## Overview

The watch runs a lightweight foreground service that continuously monitors ambient audio through a native **WebRTC VAD** (Voice Activity Detection) engine. When speech is detected, it is Opus-encoded on-device and streamed over the **Wear Data Layer** directly to the official **Omi Android app**, which handles transcription, memory creation, and cloud sync — no separate companion app needed.

> **📱 Previous versions** shipped with a standalone Android companion app (`Omi4wOS_Mobile`). This has been **deprecated** in favor of direct integration into the Omi app itself via a [WearOS AudioSource plugin](https://github.com/neurocis/omi/tree/feature/wearos-audio-source), providing a cleaner, single-app experience on the phone.

---

## Architecture

```
┌───────────────────────────────────────────┐
│              WearOS Watch                 │
│                                           │
│  AudioRecord (16 kHz · PCM16 · mono)      │
│       ↓                                   │
│  30s Circular Buffer (480K samples RAM)   │
│       ↓                                   │
│  WebRTC VAD  (960 ms evaluation windows)  │
│       ↓                                   │
│  Dynamic Hysteresis State Machine         │
│    IDLE  → 3.0 s gate · 4-frame onset     │
│    ACTIVE → 0.5 s gate · 1-frame onset    │
│       ↓                                   │
│  4.0 s Pre-roll + 1.5 s Post-roll         │
│       ↓                                   │
│  Opus Encoder (MediaCodec · 24 kbps)      │
│       ↓                                   │
│  Store-and-Forward Cache (≤ 500 MB disk)  │
│       ↓                                   │
│  Batch Blast via Wear Data Layer          │
└───────────────────────────────────────────┘
               ↓  MessageClient (binary Opus chunks)
┌───────────────────────────────────────────┐
│         Omi Android App (Flutter)         │
│                                           │
│  WearOsListenerService  (Kotlin native)   │
│       ↓                                   │
│  WearOsAudioBridge  (EventChannel)        │
│       ↓                                   │
│  WearOsSource  (AudioSource interface)    │
│       ↓                                   │
│  Existing Omi Pipeline:                   │
│    • WAL (offline cache + batch sync)     │
│    • WebSocket → wss://api.omi.me/v4/listen│
│    • Real-time transcription + memories   │
└───────────────────────────────────────────┘
```

## Quick Install

A pre-built watch APK is available in [`/releases`](releases/):

| File | Target | Install |
|------|--------|---------|
| `Omi4wOS_Wear_v1.0.apk` | WearOS watch | `adb install Omi4wOS_Wear_v1.0.apk` |

You also need the **Omi Android app** with WearOS support on your phone — see [Omi App Integration](#omi-app-integration) below.

### Install watch APK via ADB

```bash
# Enable Developer Options on watch:
#   Settings → System → About → tap Build Number 7 times
#   Settings → Developer options → enable ADB debugging + Wireless debugging

# Pair (one-time)
adb pair <WATCH_IP>:<PAIR_PORT>    # enter pairing code shown on watch

# Connect
adb connect <WATCH_IP>:5555

# Install
adb install releases/Omi4wOS_Wear_v1.0.apk
```

### Build from source

```bash
git clone https://github.com/neurocis/omi4WearOS.git
cd omi4WearOS
./gradlew assembleDebug
# Output: wear/build/outputs/apk/debug/wear-debug.apk
```

Requires JDK 17 and Android SDK 34.

---

## Omi App Integration

The watch streams audio to the official Omi Android app through a minimal, additive integration — **4 new files, ~480 lines of code, zero existing code modified:**

| File | Layer | Purpose |
|------|-------|---------|
| `WearOsListenerService.kt` | Android native | Receives binary audio chunks via Wear Data Layer `MessageClient` |
| `WearOsAudioBridge.kt` | Android native | Singleton `EventChannel` + `MethodChannel` bridge to Flutter |
| `wearos_source.dart` | Flutter | `AudioSource` implementation — converts Opus payloads into WAL frames |
| `wearos_service.dart` | Flutter | Singleton service managing connection state and audio streams |

The integration plugs into the Omi app's existing `CaptureProvider` pipeline, so watch audio is processed identically to BLE Omi hardware devices and the phone microphone — including WAL caching, WebSocket real-time transcription, and memory creation.

**Branch:** [`feature/wearos-audio-source`](https://github.com/neurocis/omi/tree/feature/wearos-audio-source)

---

## How It Works

1. **Continuous Monitoring** — A foreground service polls the microphone in `960 ms` evaluation windows (48 × 20 ms WebRTC frames), allowing the CPU to sleep between reads for minimal battery drain.

2. **Voice Activity Detection** — Each window is evaluated by a native C++ WebRTC Gaussian Mixture Model running in `VERY_AGGRESSIVE` mode. A 30-second circular buffer retains recent audio in RAM.

3. **Dynamic Hysteresis** — A two-state machine manages speech boundaries:
   - **IDLE mode** — Requires 3.0 s of sustained speech across 4 consecutive positive frames to trigger (rejects traffic, TV, background noise).
   - **ACTIVE mode** — Drops to 0.5 s / 1 frame for responsive conversational capture. Reverts to IDLE after 60 s of silence.

4. **Pre-roll & Encoding** — When speech is confirmed, 4.0 s of pre-roll audio is extracted from the circular buffer to capture the beginning of the utterance. The segment is Opus-encoded at 24 kbps via Android `MediaCodec`.

5. **Store-and-Forward** — Encoded chunks are written to the watch's internal storage (`ChunkRepository`, capped at 500 MB). If Bluetooth is connected, chunks are batch-transmitted at phrase boundaries via the Wear `MessageClient`. If disconnected, a background worker syncs them chronologically upon reconnection.

6. **Omi Processing** — The Omi app receives chunks through its `WearOsAudioSource`, feeding them into the standard WAL + WebSocket transcription pipeline for real-time processing and memory creation.

---

## Modules

| Module | Description | Key Files |
|--------|-------------|-----------|
| `:wear` | WearOS watch app | `AudioCaptureService.kt` (404 lines) · `SpeechClassifier.kt` · `OpusEncoder.kt` · `CircularAudioBuffer.kt` · `ChunkRepository.kt` · `DataLayerSender.kt` |
| `:shared` | Common data models | `AudioChunk.kt` · `Constants.kt` · `DataLayerPaths.kt` |

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose · Wear Material |
| Voice Detection | WebRTC C++ GMM (`android-vad:webrtc:2.0.7`) |
| Audio Encoding | Opus via Android MediaCodec (24 kbps · 20 ms frames) |
| Data Transport | Wear Data Layer `MessageClient` |
| Local Caching | File-based store-and-forward (`ChunkRepository`) |
| Min SDK | 30 (Android 11 / WearOS 3) |
| Target SDK | 34 |

## Key Technical Parameters

| Parameter | Value |
|-----------|-------|
| Sample rate | 16 kHz mono PCM16 |
| VAD evaluation window | 960 ms (48 × 20 ms frames) |
| Circular buffer | 30 s (480,000 samples) |
| Pre-roll | 4.0 s |
| Post-roll | 1.5 s |
| Idle gate | 3.0 s sustained speech |
| Active gate | 0.5 s sustained speech |
| Conversation timeout | 60 s → revert to IDLE |
| Opus bitrate | 24 kbps |
| Opus frame size | 20 ms (320 samples) |
| Max cache | 500 MB on-watch |
| Max segment length | 60 s |
| Data Layer path | `/audio/speech` |

---

## Prerequisites

- A **WearOS watch** running Android 11+ (Wear OS 3+)
- An **Android phone** with the [Omi app](https://github.com/neurocis/omi/tree/feature/wearos-audio-source) (WearOS-enabled build)
- For building: Android Studio Hedgehog+, JDK 17, Android SDK 34

## License

See [LICENSE](LICENSE) for details.
