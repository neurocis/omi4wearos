# omi4wOS — WearOS Audio Classification & Omi Integration

A multi-module Android/Kotlin project that enables continuous speech detection on a WearOS watch, forwards speech audio to a companion phone app, transcribes it, and uploads transcripts to [Omi](https://www.omi.me/).

## Architecture

```
┌─────────────────────────────────────┐
│           WearOS Watch              │
│                                     │
│  AudioRecord (16kHz PCM16 mono)     │
│       ↓                             │
│  YAMNet TFLite (speech detection)   │
│       ↓                             │
│  Circular Buffer + Opus Encoder     │
│       ↓                             │
│  Wear Data Layer → Phone            │
└─────────────────────────────────────┘
              ↓  BLE/WiFi
┌─────────────────────────────────────┐
│         Phone Companion             │
│                                     │
│  WearableListenerService            │
│       ↓                             │
│  Android SpeechRecognizer (STT)     │
│       ↓                             │
│  Room DB (transcript history)       │
│       ↓                             │
│  Omi REST API (upload)              │
└─────────────────────────────────────┘
```

## Modules

| Module    | Description                                      |
|-----------|--------------------------------------------------|
| `:shared` | Common data models, constants, Data Layer paths  |
| `:wear`   | WearOS watch app — recording, classification     |
| `:mobile` | Phone companion — transcription, Omi upload      |

## Tech Stack

| Component         | Technology                        |
|-------------------|-----------------------------------|
| Language          | Kotlin                            |
| UI (Watch)        | Compose for Wear OS 1.4.0         |
| UI (Phone)        | Jetpack Compose + Material3       |
| ML Inference      | TensorFlow Lite 2.16.1 + YAMNet   |
| Audio Encoding    | Opus via MediaCodec (PCM fallback)|
| Watch↔Phone       | Wear Data Layer API 18.2.0        |
| HTTP Client       | OkHttp 4.12.0                     |
| Local DB          | Room 2.6.1                        |
| Preferences       | DataStore                         |
| Build             | Gradle Kotlin DSL, AGP 8.5.0      |
| Min SDK           | 30 (Watch), 28 (Phone)            |
| Target SDK        | 34                                |

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- **Android SDK 34**
- A WearOS watch (or emulator) running Android 11+
- An Android phone running Android 9+

## Setup & Build

### 1. Clone and open

```bash
cd omi4wos
```

Open the project in Android Studio.

### 2. Download the YAMNet model

The YAMNet TFLite model (~3.7 MB) is required for speech classification on the watch.

```bash
chmod +x scripts/download-yamnet.sh
./scripts/download-yamnet.sh
```

This downloads the model to `wear/src/main/assets/yamnet.tflite`.

**Manual download alternative:**
1. Go to [TF Hub — YAMNet TFLite](https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1)
2. Download the `.tflite` file
3. Save it to `wear/src/main/assets/yamnet.tflite`

### 3. Configure local.properties

Ensure `local.properties` points to your Android SDK:

```properties
sdk.dir=/path/to/your/Android/sdk
```

### 4. Build

```bash
# Build all modules
./gradlew assembleDebug

# Build watch app only
./gradlew :wear:assembleDebug

# Build phone app only
./gradlew :mobile:assembleDebug
```

### 5. Install

```bash
# Install watch app (connect watch via ADB)
adb -s <watch-device-id> install wear/build/outputs/apk/debug/wear-debug.apk

# Install phone app
adb -s <phone-device-id> install mobile/build/outputs/apk/debug/mobile-debug.apk
```

## Configuration

### Omi API Setup

1. Open the **Omi mobile app** on your phone
2. Go to Apps → Create App
3. Enable **External Integration** and **Imports** capabilities
4. Generate an **API Key** (starts with `sk_`)
5. Note your **App ID** and **User ID**
6. Open the **omi4wOS Companion** app on your phone
7. Go to Settings and enter your API Key, App ID, and User ID

### How It Works

1. **Watch** continuously records audio at 16kHz via a foreground service
2. **YAMNet** classifies each 0.975s window — if speech is detected (confidence > 0.5), the audio is captured
3. Speech segments are **Opus-encoded** and sent to the phone via the **Wear Data Layer**
4. The **phone companion** receives audio, transcribes it using Android's **SpeechRecognizer**
5. Transcripts are stored in a local **Room database** and uploaded to **Omi** via REST API

## Project Structure

```
omi4wos/
├── build.gradle.kts          # Root build file
├── settings.gradle.kts        # Module includes
├── gradle.properties          # Gradle configuration
├── gradlew                    # Gradle wrapper
├── scripts/
│   └── download-yamnet.sh     # YAMNet model downloader
├── shared/                    # :shared module
│   └── src/main/java/com/omi4wos/shared/
│       ├── Constants.kt       # Shared constants
│       ├── AudioChunk.kt      # Audio data model
│       └── DataLayerPaths.kt  # Data Layer paths & keys
├── wear/                      # :wear module (WearOS)
│   └── src/main/
│       ├── assets/            # yamnet.tflite goes here
│       ├── java/com/omi4wos/wear/
│       │   ├── MainActivity.kt
│       │   ├── presentation/  # Compose UI
│       │   ├── service/       # Foreground service + Data Layer
│       │   ├── audio/         # Recording, classification, encoding
│       │   └── tile/          # Wear OS Tile
│       └── AndroidManifest.xml
├── mobile/                    # :mobile module (Phone)
│   └── src/main/
│       ├── java/com/omi4wos/mobile/
│       │   ├── MainActivity.kt
│       │   ├── ui/            # Compose UI
│       │   ├── service/       # Audio receiver + transcription
│       │   ├── omi/           # Omi API client + config
│       │   ├── data/          # Room database
│       │   └── viewmodel/     # ViewModels
│       └── AndroidManifest.xml
└── README.md
```

## Key Components

### Watch (`:wear`)

| Class                  | Purpose                                           |
|------------------------|---------------------------------------------------|
| `AudioCaptureService`  | Foreground service orchestrating the audio pipeline|
| `AudioRecorder`        | Wraps `AudioRecord` for 16kHz PCM16 recording     |
| `SpeechClassifier`     | YAMNet TFLite inference for speech detection       |
| `CircularAudioBuffer`  | Thread-safe ring buffer for recent audio           |
| `OpusEncoder`          | Compresses speech segments before sending          |
| `DataLayerSender`      | Sends audio chunks to phone via MessageClient      |
| `HomeScreen`           | Wear Compose UI with toggle and status             |

### Phone (`:mobile`)

| Class                  | Purpose                                           |
|------------------------|---------------------------------------------------|
| `AudioReceiverService` | WearableListenerService receiving watch audio      |
| `TranscriptionService` | Speech-to-text using Android SpeechRecognizer      |
| `OmiApiClient`         | REST client for Omi conversation upload            |
| `OmiConfig`            | DataStore-based preferences for Omi credentials    |
| `TranscriptDatabase`   | Room database for transcript storage               |
| `HomeScreen`           | Connection status + transcript list                |
| `SettingsScreen`       | Omi API configuration                              |

## Battery Considerations

- YAMNet is very efficient (~3.7 MB model, ~30ms inference)
- Non-speech audio is discarded immediately — no buffering or encoding
- Opus compression reduces data transfer bandwidth
- The watch only sends audio when speech is detected
- Consider adding duty cycling for extended battery life

## Future Enhancements

- **WebSocket streaming** to `wss://api.omi.me/v4/listen` for real-time transcription
- **Vosk/Whisper** integration for better offline STT
- **Adaptive duty cycling** based on ambient noise
- **Fine-tuned YAMNet** for improved speech detection accuracy
- **Batch upload** of pending transcripts when network is available

## License

MIT
