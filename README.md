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
│  Native WebRTC VAD (0.96s buffer)    │
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
| `:wear`   | WearOS watch app — native AudioRecord, WebRTC GMM detection, and batch opus compression |
| `:mobile` | Phone companion — Bluetooth aggregator, `.bin` compiler, and Firebase token auto-refresh |

## Tech Stack

| Component         | Technology                        |
|-------------------|-----------------------------------|
| Language          | Kotlin                            |
| UI (Watch/Phone)  | Jetpack Compose + Material3       |
| ML Inference      | Native WebRTC C++ Gaussian Mixture|
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

1. **Watch** continuously monitors audio natively, routing hardware microphone polling buffers deep inside the DSP strictly every ~1-second (`960ms`) to completely bypass Android OS CPU wake lock draw.
2. Instead of massive 500-class ML networks, a highly optimized **native C++ WebRTC Engine** performs voice activity detection on the raw PCM array. 
3. The watch aggregates high-quality Opus speech slices securely into a linear block of memory entirely silently.
4. When the WebRTC engine flags a closing silence gap, the watch bursts the completely constructed structured chunk payload concurrently over the BLE Data Layer natively (`isFinal=true`), minimizing Bluetooth wakes per sentence to exactly `1`!
5. The **Phone** listens synchronously, elegantly catching the native Opus payload array.
6. The Phone then rapidly uploads the `recording_fs320_[timestamp].bin` straight into the Omi Cloud over dual Firebase Authentication.

## Key Upgrades from Original Base

- **Completely bypassed Android SpeechRecognizer**: The unreliability of Google's local transcription engine on older phones is eliminated.
- **Extreme Battery WebRTC VAD Integration:** Shredded Google's 5MB TensorFlow Lite `YAMNet` framework out of the watch completely. We evaluate speech directly utilizing Chrome WebRTC Gaussian Mixture algorithms without neural net processing delays!
- **Data Layer Batch Blasting:** Prevented the watch from keeping the low-energy Bluetooth radio hyper-active every 1 second during sentences. The OS restricts Bluetooth to a single serialized payload blast precisely when the transcript naturally ends!
- **Flawless Bluetooth De-duplication**: Dual Samsung WearableListeners are strictly filtered using immutable Chunk Index IDs to eliminate audio stuttering over bad connections.
- **Intelligent Pre-roll Windows**: A massive 2.5s pre-roll algorithmic capture entirely eradicates "cut-off" beginnings of sentences.
- **Invisible Auto-Retry Layer**: Failed bin uploads gracefully fall back onto local cache and instantaneously retry uploading without manual intervention implicitly triggered upon app wake.
