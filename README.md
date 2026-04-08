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

1. **Watch**: Continuously monitors audio by routing microphone polling buffers into the DSP every `960ms`, ensuring the OS CPU can sleep between reads.
2. **WebRTC VAD**: A native C++ WebRTC Engine running in a background process evaluates the audio buffer for voice activity.
3. **Buffering**: Opus-encoded speech frames are batched into a linear block of memory.
4. **Data Transmission**: Once voice activity ceases, the watch sends the complete structured payload across the Wear Data Layer. This minimizes Bluetooth wake locks to one per sentence.
5. **Phone**: The companion app listens on the Data Layer, assembling the received Opus payloads into an `.bin` archive.
6. **Cloud Upload**: The phone pushes the compiled `recording.bin` into the Omi Cloud using standard Firebase Authentication tokens.

## Key Upgrades from Original Base

- **Direct Cloud Integration:** Removed Android SpeechRecognizer, assembling standard Limitless-compatible `.bin` archives that upload directly to the `/v2/sync-local-files` endpoint.
- **WebRTC VAD Integration:** Replaced the 5MB TensorFlow Lite `YAMNet` framework with native Chromium WebRTC Gaussian Mixture algorithms, drastically reducing battery consumption and memory footprint.
- **BLE Batch Transfer:** Changed the transmission behavior from streaming every second to batch-transmitting the completed payload at the end of a phrase to reduce radio activity.
- **Store-and-Forward Caching Engine:** Includes a local disk-caching mechanism (`ChunkRepository`). If Bluetooth connection drops, the watch saves up to 500MB of Opus audio to disk. A background worker syncs the missed files chronologically upon reconnection.
- **Duplicate Audio Prevention**: Companion dual-listeners track immutable Chunk Index IDs to drop stuttering or duplicated chunks in bad connections.
- **Algorithmic Pre-roll Windows**: Buffers 2.5s of audio prior to speech detection to prevent sentence cutoff.
- **Background Upload Retry**: Failed `.bin` uploads are cached natively in a local Room database and re-attempted on next internet connection.
