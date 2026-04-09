# omi4wearOS — WearOS Watch Module for Omi

A WearOS watch app that transforms your smartwatch into a natively integrated [Omi](https://www.omi.me/) speech companion.

The watch continuously monitors audio using a native WebRTC VAD engine, Opus-encodes detected speech, and streams it to the official **Omi Android app** via the Wear Data Layer — no standalone companion app required.

<div align="center">
  <img src="https://github.com/user-attachments/assets/e8de0170-4512-4a55-bc84-e5f4a6d9b833" width="250" />
  <img src="https://github.com/user-attachments/assets/096cb612-54f4-4a26-b7c9-17a58bdb2d81" width="250" />
  <img src="https://github.com/user-attachments/assets/825d95c0-ba44-47fa-b80b-8b0d1220ca15" width="250" />
</div>

> **⚠️ Deprecation Notice:** The standalone mobile companion app (`Omi4wOS_Mobile`) has been deprecated. WearOS audio is now routed directly into the official Omi Android app via a [WearOS AudioSource integration](https://github.com/neurocis/omi/tree/feature/wearos-audio-source). See [Omi App Integration](#omi-app-integration) below.

## Architecture

```
┌──────────────────────────────────────┐
│            WearOS Watch              │
│                                      │
│  AudioRecord  (16kHz PCM16 mono)     │
│       ↓                              │
│  Native WebRTC VAD (0.96s buffer)    │
│       ↓                              │
│  Linear Buffer + Opus Encoder API    │
│       ↓                              │
│  BLE Batch Blast (on phrase end)     │
└──────────────────────────────────────┘
              ↓  Wear Data Layer (Native Opus Streams)
┌──────────────────────────────────────┐
│       Omi Android App (Flutter)      │
│                                      │
│  WearOsListenerService (Kotlin)      │
│       ↓                              │
│  WearOsAudioBridge (EventChannel)    │
│       ↓                              │
│  WearOsSource (AudioSource)          │
│       ↓                              │
│  WAL + WebSocket Transcription       │
│       ↓                              │
│  Omi Cloud (Real-time + Batch Sync)  │
└──────────────────────────────────────┘
```

## Modules

| Module    | Description                                      |
|-----------|--------------------------------------------------|
| `:shared` | Common data models, constants, Data Layer paths  |
| `:wear`   | WearOS watch app — native AudioRecord, WebRTC GMM detection, and batch Opus compression |

## Tech Stack

| Component         | Technology                        |
|-------------------|-----------------------------------|
| Language          | Kotlin                            |
| UI (Watch)        | Jetpack Compose + Material3       |
| Voice Detection   | Native WebRTC C++ Gaussian Mixture|
| Audio Encoding    | Opus via Android MediaCodec 16kbps|
| Data Transport    | Wear Data Layer (MessageClient)   |

## Prerequisites

- Android Studio Hedgehog (2023.1.1)+
- JDK 17, Android SDK 34
- A WearOS watch (Android 11+)
- The [Omi Android app](https://github.com/neurocis/omi/tree/feature/wearos-audio-source) with WearOS integration

## Installation

### Build from source

```bash
git clone https://github.com/neurocis/omi4wearos.git
cd omi4wearos
./gradlew assembleDebug
```

The watch APK will be at `wear/build/outputs/apk/debug/wear-debug.apk`.

### Install on watch via ADB

```bash
# Connect watch via Wi-Fi debugging
adb pair <WATCH_IP>:<PORT>      # Use pairing code from watch
adb connect <WATCH_IP>:5555

# Install
adb install wear-debug.apk
```

## Omi App Integration

The WearOS watch audio is now received directly by the official Omi Android app through a minimal integration:

| Component | Description |
|-----------|-------------|
| `WearOsListenerService.kt` | Native WearableListenerService receiving audio via Data Layer |
| `WearOsAudioBridge.kt` | EventChannel/MethodChannel bridge from native Kotlin to Flutter |
| `wearos_source.dart` | Flutter AudioSource implementation for WearOS Opus audio |
| `wearos_service.dart` | Flutter singleton managing WearOS connection and audio streams |

See the integration PR: [neurocis/omi#feature/wearos-audio-source](https://github.com/neurocis/omi/tree/feature/wearos-audio-source)

## How It Works

1. **Watch**: Continuously monitors audio by routing microphone polling buffers into the DSP every `960ms`, ensuring the OS CPU can sleep between reads.
2. **WebRTC VAD**: A native C++ WebRTC Engine running in a background process evaluates the audio buffer for voice activity.
3. **Local Caching**: Audio containing voice activity is Opus-encoded and immediately serialized to the watch's internal filesystem. This ensures no data is lost if the watch is away from the phone.
4. **Data Transmission**: Once a sentence concludes, the watch evaluates Bluetooth connectivity. If connected, it batch-transmits all pending payloads across the Wear Data Layer to the Omi app. If disconnected, files are securely retained until a background worker syncs them upon reconnection.
5. **Omi App**: The Omi app receives the audio through its `WearOsAudioSource`, routing it through the existing WAL + WebSocket transcription pipeline — identical to how BLE Omi devices and the phone mic are handled.

## Key Features

- **Direct Omi Integration:** Audio flows directly into the Omi app's existing pipeline — no standalone companion required.
- **WebRTC VAD:** Native Chromium WebRTC Gaussian Mixture algorithms for efficient voice activity detection with minimal battery impact.
- **BLE Batch Transfer:** Transmits completed phrases at segment end instead of streaming per-second, reducing radio activity.
- **Store-and-Forward Caching:** Local disk-caching mechanism (`ChunkRepository`) saves up to 500MB of Opus audio when Bluetooth drops. Background worker syncs on reconnection.
- **Dynamic Conversational Hysteresis:** Switches between strict 3.8s environmental noise wall (Idle) and aggressive 0.9s word-catcher (Active Conversation) for false-positive prevention without sacrificing responsiveness.
- **Amplified Pre-roll Windows:** Buffers 4.0s of audio backwards through RAM prior to speech detection to prevent sentence cutoff.
