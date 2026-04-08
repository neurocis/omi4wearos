# omi4wOS — cipioh Direct Cloud Sync Edition

A multi-module Android/Kotlin project that transforms your WearOS watch into an incredibly efficient, natively integrated Omi speech companion. 

Adapted the original version which performed Android transcription, **this cipioh-edition rewrite fundamentally redesigns the architectural flow to precisely emulate the Omi device.** The watch now generates native 16kbps Opus chunks, seamlessly streams them to the phone companion, and directly constructs Omi-compatible `.bin` archives that are pushed natively into Omi's `/v2/sync-local-files` cloud API for state-of-the-art server-side transcription and intelligence.

<div align="center">
  <img src="https://github.com/user-attachments/assets/e8de0170-4512-4a55-bc84-e5f4a6d9b833" width="250" />
  <img src="https://github.com/user-attachments/assets/096cb612-54f4-4a26-b7c9-17a58bdb2d81" width="250" />
  <img src="https://github.com/user-attachments/assets/825d95c0-ba44-47fa-b80b-8b0d1220ca15" width="250" />
</div>

## Architecture

```
┌──────────────────────────────────────┐
│            WearOS Watch              │
│                                      │
│  AudioRecord  (16kHz PCM16 mono)     │
│       ↓                              │
│  YAMNet TFLite (0.975s window)       │
│       ↓                              │
│  Linear Buffer + Opus Encoder API    │
│       ↓                              │
│  Wear Data Layer → Phone             │
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
| `:wear`   | WearOS watch app — recording, YAMNet classification, native Opus encoding |
| `:mobile` | Phone companion — Bluetooth aggregator, `.bin` compiler, and Firebase token auto-refresh |

## Tech Stack

| Component         | Technology                        |
|-------------------|-----------------------------------|
| Language          | Kotlin                            |
| UI (Watch/Phone)  | Jetpack Compose + Material3       |
| ML Inference      | TensorFlow Lite + YAMNet (Local)  |
| Audio Encoding    | Opus via Android MediaCodec 16kbps|
| Cloud Upload      | Multipart POST v2/sync-local-files|
| Authentication    | Omi Firebase IndexedDB tokens     |

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

1. **Watch** continuously monitors audio using an ultra-lightweight YAMNet local ML model for voice activity.
2. When speech is detected, the watch aggressively extracts a highly optimized Opus audio chunk out of a completely sequential, duplicate-free linearly tracked buffer and streams it over Bluetooth.
3. The **Phone** listens synchronously, intelligently appending native Opus payloads together matching Omi frame formatting.
4. Once the sentence drops below the noise floor, the Phone securely uploads `recording_fs320_[timestamp].bin` directly into the Omi Cloud over dual Firebase Authentication.

## Key Upgrades from Original Base

- **Completely bypassed Android SpeechRecognizer**: The unreliability of Google's local transcription engine on older phones is eliminated.
- **Flawless Bluetooth De-duplication**: Dual Samsung WearableListeners are strictly filtered using immutable Chunk Index IDs to eliminate audio stuttering over bad connections.
- **Intelligent Pre-roll Windows**: A massive 2.5s pre-roll algorithmic capture entirely eradicates "cut-off" beginnings of sentences triggered by speech hysteresis windows.
- **Invisible Auto-Retry Layer**: Failed bin uploads gracefully fall back onto local cache and instantaneously retry uploading without manual intervention explicitly triggered upon app wake.
