# Technical Brief: WearOS Audio Classification & Omi Integration

**Date:** 2026-04-06  
**Purpose:** Architecture and API research for building a WearOS app that records audio 24/7, classifies speech vs non-speech, and forwards speech to Omi.

---

## 1. Omi Integration API

### 1.1 Integration Paths (Ranked by Suitability)

| Path | Protocol | Auth | Audio Support | Complexity | Recommended |
|------|----------|------|---------------|------------|-------------|
| **A. WebSocket Streaming** | `wss://api.omi.me/v4/listen` | Firebase UID | ✅ Raw audio stream | Medium-High | ✅ Best for real-time |
| **B. REST Data Import** | `POST /v2/integrations/{app_id}/user/conversations` | Bearer `sk_*` API key | ❌ Text only | Low | ✅ Best for transcripts |
| **C. REST Developer API** | `POST /v1/dev/user/memories` | Bearer `omi_dev_*` key | ❌ Text only | Low | Good for memories |
| **D. BLE Device Emulation** | BLE GATT | BLE pairing | ✅ Raw audio | Very High | ❌ Over-engineered |

### 1.2 Path A: WebSocket Audio Streaming (Real-Time)

**Endpoint:**
```
wss://api.omi.me/v4/listen?uid={uid}&language={lang}&sample_rate={rate}&codec={codec}
```

**Full Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `uid` | string (required) | — | Firebase Auth user ID |
| `language` | string | `en` | Language code (`en`, `es`, `fr`, `de`, `ja`, `zh`, `multi`) |
| `sample_rate` | int | `8000` | Audio sample rate: `8000`, `16000`, `44100`, `48000` |
| `codec` | string | `pcm8` | Audio codec (see table below) |
| `channels` | int | `1` | Mono (1) or stereo (2) |
| `include_speech_profile` | bool | `true` | Enable speaker identification |
| `conversation_timeout` | int | `120` | Seconds of silence before auto-save (2–14400) |
| `stt_service` | string | — | STT provider (e.g., `deepgram`) |
| `source` | string | — | Source identifier (e.g., `omi`, `phone`) |

**Supported Codecs:**

| Codec | Sample Rate | Description | Best For |
|-------|-------------|-------------|----------|
| `pcm8` | 8kHz | 8-bit PCM | Low bandwidth (default) |
| `pcm16` | 16kHz | 16-bit PCM | Better quality |
| `opus` | 16kHz | Opus encoded | **Efficient compression** ✅ |
| `opus_fs320` | 16kHz | Opus 320 frame | Alternative frame size |
| `aac` | Variable | AAC encoded | iOS compatibility |
| `lc3` | Variable | LC3 codec | Bluetooth audio |

**Server Responses (JSON):**
- **Transcript segments:** `[{"id": "...", "text": "Hello", "speaker": "SPEAKER_00", "is_user": true, "start": 0.0, "end": 1.5}]`
- **Memory created:** `{"type": "memory_created", ...}`
- **Service status:** `{"type": "service_status", ...}`

**⚠️ Challenge:** Requires Firebase UID. Would need to either:
1. Extract UID from the user's Omi app installation (complex, requires cooperation)
2. Implement Firebase Auth in the WearOS app (adds dependency)
3. Use a companion phone app that shares the Firebase session

### 1.3 Path B: REST Data Import API (Transcript-Based) — RECOMMENDED

**Endpoint:**
```
POST https://api.omi.me/v2/integrations/{app_id}/user/conversations?uid={user_id}
```

**Headers:**
```
Authorization: Bearer sk_your_api_key_here
Content-Type: application/json
```

**Request Body:**
```json
{
    "started_at": "2024-07-21T22:34:43.384323+00:00",
    "finished_at": "2024-07-21T22:35:43.384323+00:00",
    "text": "Speaker 1: Hello, how are you?\n\nSpeaker 2: I'm doing well!",
    "text_source": "audio_transcript",
    "text_source_spec": "phone_call"
}
```

**Available Endpoints:**

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v2/integrations/{app_id}/user/conversations` | Create conversation from text |
| `POST` | `/v2/integrations/{app_id}/user/memories` | Create memories |
| `GET` | `/v2/integrations/{app_id}/conversations` | Read conversations |
| `GET` | `/v2/integrations/{app_id}/memories` | Read memories |
| `POST` | `/v1/dev/user/memories` | Create memory (dev API) |
| `POST` | `/v1/dev/user/memories/batch` | Batch create (up to 25) |

**Setup Requirements:**
1. Create an app in Omi mobile app with **External Integration** + **Imports** capabilities
2. Generate API key from app management page
3. User must enable your app in their Omi account

### 1.4 Path C: Developer Memory API

**Endpoint:**
```
POST https://api.omi.me/v1/dev/user/memories
Authorization: Bearer omi_dev_your_key_here
```

**Body:**
```json
{
    "content": "User discussed project deadlines at 3pm meeting",
    "category": "interesting",
    "visibility": "private",
    "tags": ["meeting", "work"]
}
```

**API Key:** Generated in Omi app → Settings → Developer → Create Key

---

## 2. Omi Android App Architecture

### 2.1 Overview

The official Omi app is a **Flutter** application (not native Android/Kotlin). The Android directory at `omi/app/android` is the Flutter Android host module.

### 2.2 Audio Pipeline (Device → App → Backend)

```
Omi Hardware Device
    ↓ BLE GATT (Opus @ 16kHz, 80-byte frames)
Omi Flutter App (Phone)
    ↓ WebSocket (wss://api.omi.me/v4/listen)
Omi Backend (Deepgram STT)
    ↓ Returns transcript segments
Omi App displays results
```

### 2.3 BLE Protocol Details

| Component | UUID | Purpose |
|-----------|------|---------|  
| Audio Codec Characteristic | `19b10002` | Read to determine streaming codec |
| Features Service | `19b10020` | Hardware capability discovery |
| Storage Data Stream Service | `30295780` | Offline audio retrieval |
| Storage Data Stream | `30295781` | Offline audio data |
| Storage Read Control | `30295782` | Offline storage control |

**Supported Codecs (BLE):**

| Codec | Sample Rate | Bit Depth | Frames/sec | Frame Size |
|-------|-------------|-----------|------------|------------|
| `opus` (default) | 16 kHz | 16-bit | 100 | 80 bytes |
| `opusFS320` | 16 kHz | 16-bit | 50 | 160 bytes |
| `pcm16` | 16 kHz | 16-bit | 100 | 80 bytes |
| `pcm8` | 16 kHz | 8-bit | 100 | 80 bytes |

**BLE Transport Config:**
- L2CAP TX MTU: 498 bytes
- ACL TX buffer: 2048 bytes
- Connection intervals: 7.5–30 ms
- PHY: 2M mode
- Max 1 concurrent BLE connection
- TX power: +8 dBm

### 2.4 Key Architecture Classes

```
DeviceConnectionFactory.create(device) → OmiDeviceConnection
    ↓ connect()
    ↓ performSyncTime()
    ↓ read audioCodec characteristic (19b10002)
    ↓ read features characteristic (19b10020) → OmiFeatures bitmask
    ↓ subscribe to BLE audio notifications
    ↓ captureProvider.streamDeviceRecording()
    ↓ periodicConnect() — 15s reconnection timer
```

### 2.5 Integration Approach for Our WearOS App

**Option 1 — BLE Peripheral Emulation (NOT recommended):**
Our WearOS app would emulate an Omi BLE device, advertising the same GATT services. The Omi phone app would connect to it like a real device. This is fragile and complex.

**Option 2 — Direct API Integration (RECOMMENDED):**
Our WearOS app records audio → classifies speech → transcribes locally or streams to Omi backend via WebSocket/REST API. Independent of the Omi phone app's BLE flow.

---

## 3. SoundWatch Audio Classification

### 3.1 Model Architecture

SoundWatch evaluated four models:

| Model | Size | Accuracy (all 20 sounds) | Watch Latency | Recommendation |
|-------|------|--------------------------|---------------|----------------|
| **VGG-lite** | 281.8 MB | **81.2%** | 3,397 ms | ❌ Too large/slow for always-on |
| ResNet-lite | 178.3 MB | 65.1% | — | ❌ Too large |
| MobileNet | 3.4 MB | 26.5% | 256 ms | ❌ Too inaccurate |
| Inception | 41 MB | <40% | — | ❌ Poor accuracy |

**Selected in paper:** VGG-lite (best accuracy at 81.2%)

### 3.2 Audio Processing Pipeline

```
Microphone (16 kHz sampling)
    ↓
1-second sliding window (16,000 samples)
    ↓
Log mel-spectrogram features (Hershey et al. method)
    ↓
Neural network classification
    ↓
Filter: confidence ≥ 50% AND loudness ≥ 45 dB
    ↓
Notification to user
```

**Key Parameters:**
- Sample rate: **16 kHz**
- Window size: **1 second** (16,000 samples)
- Features: **Log mel-spectrogram** (not MFCC as README suggests)
- Feature extraction: Python via **Chaquopy** bridge
- Inference: **TensorFlow Lite**
- Confidence threshold: **50%**
- Loudness threshold: **45 dB**

### 3.3 Sound Classes (20 total)

| Category | Sounds |
|----------|--------|
| **High Priority** | Fire/smoke alarm, Alarm clock, Door knock |
| **Home** | Doorbell, Door-in-use, Microwave, Washer/dryer, Phone ringing, **Speech**, Laughing, Water running |
| **Outdoor** | Vehicle running, Car horn, Siren, Bird chirp, Dog bark, Cat meow |
| **Other** | Baby crying, Hammering, Drilling |

### 3.4 Device Architectures

| Architecture | Latency | CPU | Network | Recommendation |
|-------------|---------|-----|---------|----------------|
| Watch-only | 5.9s | Very High | None | ❌ Too slow |
| **Watch+Phone** | **2.2s** | Moderate | BLE | ✅ Best balance |
| Watch+Phone+Cloud | 1.8s | Low | WiFi needed | Good but needs internet |
| Watch+Cloud | 2.4s | Low | WiFi needed | OK but watch WiFi unreliable |

### 3.5 Recommended Alternative: YAMNet TFLite

For our use case, we only need **speech detection** (binary: speech vs. non-speech), not all 20 SoundWatch categories. **YAMNet** is a superior choice:

| Feature | YAMNet | SoundWatch VGG-lite |
|---------|--------|--------------------|
| Model size | **~3.7 MB** | 281.8 MB |
| Architecture | MobileNet v1 | VGG (quantized) |
| Classes | 521 (AudioSet) | 20 |
| Includes Speech | ✅ Yes | ✅ Yes |
| TFLite support | ✅ Native | ✅ Yes |
| Inference speed | **~20-50ms** | 3,397 ms |
| Pre-trained | ✅ TF Hub | Custom trained |
| Input | 16kHz mono PCM | 16kHz mono |
| Features | Log mel-spectrogram (internal) | Log mel-spectrogram (external Python) |

**YAMNet advantages for our case:**
- Tiny model (3.7 MB vs 281.8 MB) — fits easily on WearOS
- Blazing fast inference (~20-50ms) — critical for always-on classification
- Computes mel-spectrogram internally — no need for Chaquopy/Python bridge
- 521 classes including "Speech" (class index in AudioSet ontology)
- Available as TFLite from TensorFlow Hub
- Can be fine-tuned or used with transfer learning for better speech detection

---

## 4. Chaquopy (Python on Android)

### 4.1 Overview

- **Purpose:** Run Python code within Android apps (including WearOS)
- **Current version:** 17.0.0
- **Python versions:** 3.10 (default), 3.11, 3.12, 3.13, 3.14
- **Min SDK:** 24 (Android 7.0)
- **Supported ABIs:** `arm64-v8a`, `x86_64` (64-bit only for Python 3.12+)

### 4.2 Integration

**build.gradle (top-level):**
```gradle
plugins {
    id("com.chaquo.python") version "17.0.0" apply false
}
```

**build.gradle (app module):**
```gradle
plugins {
    id("com.chaquo.python")
}

android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("numpy==1.24.0")
        }
    }
}
```

**Runtime initialization:**
```kotlin
if (!Python.isStarted()) {
    Python.start(AndroidPlatform(context))
}
val py = Python.getInstance()
val module = py.getModule("my_audio_processor")
val result = module.callAttr("process_audio", audioData)
```

### 4.3 WearOS Compatibility

- **Proven:** SoundWatch already uses Chaquopy on WearOS successfully
- **Constraint:** Adds APK size (Python runtime + packages)
- **For our project:** Likely **NOT needed** if we use YAMNet TFLite directly (it handles mel-spectrogram internally), avoiding the Python overhead entirely

### 4.4 Supported Packages (Relevant)

- `numpy` ✅ (used by SoundWatch)
- `scipy` ✅
- `librosa` — check availability (may need subset)
- Pure Python packages ✅ (almost all)
- Native packages — large selection pre-built

### 4.5 Recommendation for Our Project

**Skip Chaquopy** — Use YAMNet TFLite which handles feature extraction internally in C++. This eliminates:
- Python runtime overhead (~15-30 MB)
- Chaquopy build complexity
- Python↔Kotlin bridge latency
- NumPy/SciPy dependencies

---

## 5. WearOS Audio Recording

### 5.1 API: `android.media.AudioRecord`

WearOS uses the same `AudioRecord` API as phone Android. The official Android docs confirm: *"Recording audio on a Wear OS device works the same way as it would on a phone."*

### 5.2 Required Permissions & Manifest

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".AudioRecordingService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

### 5.3 Foreground Service Pattern

```kotlin
class AudioRecordingService : Service() {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification() // Required persistent notification
        startForeground(
            NOTIFICATION_ID, 
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        startRecording()
        return START_STICKY
    }
    
    private fun startRecording() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2 // Extra buffer for safety
        )
        
        isRecording = true
        Thread {
            audioRecord?.startRecording()
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    processAudioBuffer(buffer, read)
                }
            }
            audioRecord?.release()
        }.start()
    }
}
```

### 5.4 Audio Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Sample rate | **16,000 Hz** | Matches Omi WebSocket & YAMNet requirement |
| Channel | **Mono** | `CHANNEL_IN_MONO` (guaranteed on all devices) |
| Encoding | **PCM 16-bit** | `ENCODING_PCM_16BIT` |
| Buffer size | `getMinBufferSize() * 2` | Extra headroom recommended |
| Audio source | `MIC` | `MediaRecorder.AudioSource.MIC` |

**Note:** 44100 Hz is the only rate *guaranteed* on all devices, but 16000 Hz works on virtually all modern WearOS devices and matches both YAMNet and Omi requirements.

### 5.5 Limitations & Constraints

| Constraint | Impact | Mitigation |
|-----------|--------|------------|
| **Battery drain** | Continuous mic + ML = significant drain | Duty cycling, efficient model (YAMNet), Opus compression |
| **Foreground service required** | Persistent notification visible | Good UX design for notification |
| **RECORD_AUDIO runtime permission** | User must grant at runtime | Clear permission rationale |
| **Battery optimization** | WearOS may kill services aggressively | `START_STICKY`, wake lock, request battery optimization exemption |
| **Storage limits** | WearOS has limited storage | Stream or discard non-speech audio, don't buffer long |
| **RAM constraints** | WearOS watches have 1-2 GB RAM | YAMNet (3.7 MB model) is ideal; avoid large models |
| **Network connectivity** | Watch may lose phone/WiFi connection | Buffer transcripts locally, sync when connected |
| **Screen-off recording** | Works with foreground service + microphone type | Properly declare `foregroundServiceType="microphone"` |
| **Android 14+ restrictions** | Stricter foreground service type enforcement | Must declare `FOREGROUND_SERVICE_MICROPHONE` permission |

### 5.6 Wake Lock Strategy

```kotlin
val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omi4wos:audio")
wakeLock.acquire() // In onStartCommand
// wakeLock.release() // In onDestroy
```

---

## 6. Recommended Architecture

### 6.1 High-Level Architecture

```
┌─────────────────────────────────────────────────┐
│                 WearOS Watch                      │
│                                                   │
│  ┌──────────────┐    ┌──────────────────────┐    │
│  │ AudioRecord  │───▶│ YAMNet TFLite        │    │
│  │ Foreground   │    │ Speech Classifier     │    │
│  │ Service      │    │ (1s windows, ~30ms)   │    │
│  │ (16kHz PCM)  │    └──────────┬───────────┘    │
│  └──────────────┘               │                 │
│                          speech detected?          │
│                         ╱            ╲             │
│                       YES             NO           │
│                        │              │            │
│                  ┌─────▼─────┐    discard          │
│                  │ Buffer &  │                     │
│                  │ Encode    │                     │
│                  │ (Opus)    │                     │
│                  └─────┬─────┘                     │
│                        │                           │
│              ┌─────────▼──────────┐                │
│              │ Data Layer API     │                │
│              │ (to Phone)         │                │
│              └─────────┬──────────┘                │
│                        │                           │
└────────────────────────┼───────────────────────────┘
                         │ BLE/WiFi
┌────────────────────────┼───────────────────────────┐
│              Phone (Companion App)                  │
│                        │                           │
│              ┌─────────▼──────────┐                │
│              │ Receive audio/     │                │
│              │ transcript         │                │
│              └─────────┬──────────┘                │
│                        │                           │
│         ┌──────────────┼──────────────┐            │
│         ▼              ▼              ▼            │
│   ┌──────────┐  ┌───────────┐  ┌──────────┐       │
│   │ WebSocket│  │ REST API  │  │ Local    │       │
│   │ Stream   │  │ Transcript│  │ Storage  │       │
│   │ to Omi   │  │ Upload    │  │ Buffer   │       │
│   └──────────┘  └───────────┘  └──────────┘       │
│                                                    │
└────────────────────────────────────────────────────┘
                         │
              ┌──────────▼──────────┐
              │   Omi Backend       │
              │   api.omi.me        │
              └─────────────────────┘
```

### 6.2 Integration Strategy Options

#### Option A: WebSocket Audio Streaming (Best Real-Time Experience)

```
Watch → (Data Layer) → Phone Companion → WebSocket → Omi Backend
```

- Phone companion app maintains WebSocket connection to `wss://api.omi.me/v4/listen`
- Watch sends speech audio segments to phone via Wear Data Layer API
- Phone forwards audio frames to WebSocket
- Omi backend handles STT and creates conversations automatically
- **Pro:** Real-time transcription by Omi (Deepgram), speaker identification, automatic conversation management
- **Con:** Needs Firebase UID, continuous network connection, companion app complexity

#### Option B: REST Transcript Upload (Simplest, Most Reliable) — RECOMMENDED START

```
Watch → (classify + buffer) → Phone Companion → REST API → Omi Backend
```

- Watch classifies audio with YAMNet, buffers speech segments
- Periodically sends speech audio to phone via Data Layer API
- Phone runs on-device STT (Android SpeechRecognizer or Whisper) to transcribe
- Uploads transcripts to Omi via REST API as conversations
- **Pro:** Simple auth (API key), works offline with buffering, no WebSocket management
- **Con:** Not real-time, needs local STT, batch-oriented

#### Option C: Direct Watch → Omi (Minimal, Watch-Only)

```
Watch → (classify + STT) → REST API → Omi Backend
```

- Watch handles everything: recording, classification, STT, API upload
- Uses Android on-device SpeechRecognizer for STT
- Uploads directly when WiFi available
- **Pro:** No companion app needed
- **Con:** Heavy battery drain, limited WearOS network, STT quality concerns

### 6.3 Recommended Implementation Plan

**Phase 1: Core WearOS App (Watch-Only MVP)**
1. Foreground service with AudioRecord (16kHz PCM16 mono)
2. YAMNet TFLite for speech detection (1-second windows)
3. Local buffering of speech segments
4. Basic WearOS UI (recording status, toggle on/off)

**Phase 2: Phone Companion + Omi Integration**
1. Wear Data Layer API for watch→phone communication
2. Companion phone app receives audio segments
3. On-device STT (Android SpeechRecognizer or Vosk)
4. REST API upload to Omi (`/v2/integrations/{app_id}/user/conversations`)

**Phase 3: Real-Time Streaming (Enhancement)**
1. WebSocket connection to `wss://api.omi.me/v4/listen`
2. Opus encoding on watch for bandwidth efficiency
3. Real-time transcript display on watch
4. Firebase Auth integration for WebSocket authentication

**Phase 4: Optimization**
1. Adaptive duty cycling based on ambient noise levels
2. Battery optimization and power profiling
3. Offline buffering with sync-when-connected
4. Fine-tuned YAMNet for better speech/non-speech accuracy

### 6.4 Technology Stack

| Component | Technology | Rationale |
|-----------|------------|----------|
| Language | **Kotlin** | Native WearOS, modern Android |
| UI Framework | **Compose for Wear OS** | Official, modern WearOS UI |
| Audio Recording | **AudioRecord** | Low-level control, streaming access |
| ML Inference | **TFLite + YAMNet** | Tiny (3.7MB), fast (~30ms), 521 classes |
| Audio Encoding | **Opus** (via libopus/Android MediaCodec) | Efficient compression for streaming |
| Watch↔Phone | **Wear Data Layer API** | Official Google API, reliable |
| STT (Phone) | **Android SpeechRecognizer** or **Vosk** | On-device, no network needed |
| Omi Integration | **REST API** (Phase 2) / **WebSocket** (Phase 3) | Progressive complexity |
| Build System | **Gradle + Android Studio** | Standard tooling |
| Min SDK | **30** (Android 11) | Modern WearOS devices |
| Target SDK | **34** (Android 14) | Latest features |

### 6.5 Key Code Patterns

**YAMNet Speech Detection:**
```kotlin
class SpeechClassifier(context: Context) {
    private val interpreter: Interpreter
    private val SPEECH_CLASS_INDEX = 0 // "Speech" in AudioSet ontology
    private val SPEECH_THRESHOLD = 0.5f
    
    init {
        val model = FileUtil.loadMappedFile(context, "yamnet.tflite")
        interpreter = Interpreter(model)
    }
    
    fun isSpeech(audioBuffer: FloatArray): Boolean {
        // YAMNet expects 15600 samples (0.975s at 16kHz)
        val input = Array(1) { audioBuffer }
        val output = Array(1) { FloatArray(521) } // 521 AudioSet classes
        interpreter.run(input, output)
        return output[0][SPEECH_CLASS_INDEX] > SPEECH_THRESHOLD
    }
}
```

**Omi REST API Upload:**
```kotlin
suspend fun uploadToOmi(transcript: String, startTime: Instant, endTime: Instant) {
    val client = OkHttpClient()
    val json = JSONObject().apply {
        put("text", transcript)
        put("started_at", startTime.toString())
        put("finished_at", endTime.toString())
        put("text_source", "audio_transcript")
        put("text_source_spec", "wearos_watch")
    }
    val request = Request.Builder()
        .url("https://api.omi.me/v2/integrations/$APP_ID/user/conversations?uid=$USER_ID")
        .header("Authorization", "Bearer $API_KEY")
        .post(json.toString().toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(request).execute()
}
```

**Wear Data Layer Communication:**
```kotlin
// Watch side - send audio to phone
val dataClient = Wearable.getDataClient(context)
val request = PutDataMapRequest.create("/audio/speech").apply {
    dataMap.putByteArray("audio", audioBytes)
    dataMap.putLong("timestamp", System.currentTimeMillis())
    dataMap.putFloat("confidence", speechConfidence)
}.asPutDataRequest().setUrgent()
dataClient.putDataItem(request)

// Phone side - receive audio
class AudioListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.dataItem.uri.path == "/audio/speech") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val audio = dataMap.getByteArray("audio")
                // Process and send to Omi
            }
        }
    }
}
```

---

## 7. Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| Battery drain too high for 24/7 use | High | High | Duty cycling, efficient model, Opus compression |
| YAMNet speech detection accuracy insufficient | Medium | Medium | Fine-tune on speech corpus, add VAD (WebRTC VAD) as pre-filter |
| Firebase UID access for WebSocket | Medium | High | Start with REST API path, add WebSocket later |
| WearOS kills foreground service | Medium | Medium | START_STICKY, wake lock, battery optimization exemption |
| Network latency for transcript upload | Low | Medium | Local buffering, batch uploads |
| Watch storage fills up | Low | Low | Circular buffer, discard non-speech immediately |
| Omi API rate limits | Low | Low | Batch conversations, respect limits |

---

## 8. Summary & Recommendations

1. **Use YAMNet TFLite** (not SoundWatch's VGG-lite) for speech classification — 75x smaller, 100x faster
2. **Start with REST API integration** (Path B) — simplest auth, most reliable
3. **Build watch+phone architecture** — watch records & classifies, phone transcribes & uploads
4. **Skip Chaquopy** — YAMNet handles mel-spectrogram internally in C++
5. **Use Wear Data Layer API** for watch↔phone communication
6. **Add WebSocket streaming** in Phase 3 for real-time experience
7. **16kHz PCM16 mono** is the universal audio format matching both YAMNet and Omi
8. **Foreground service with MICROPHONE type** is mandatory for background recording
