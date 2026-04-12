package com.omi4wos.shared

/**
 * Shared constants used by both wear and mobile modules.
 */
object Constants {
    // Audio recording parameters
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = 1 // Mono
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    // Silero VAD parameters
    const val WEBRTC_INPUT_SAMPLES = 15360 // 0.960s at 16kHz (exactly 30 frames of 32ms)
    const val LOUDNESS_THRESHOLD_DB = 52.0 // Raised from 45dB to filter watch vibration and clothing rustle
    // Integer energy threshold equivalent to LOUDNESS_THRESHOLD_DB.
    // Derived: rms_norm = 10^((52-90)/20) ≈ 0.012589; threshold = (rms_norm * 32768)^2 ≈ 170_181
    // Used by isLoudEnough() to avoid float math (sqrt, log10) on every classification cycle.
    const val LOUDNESS_THRESHOLD_SQ = 170_181L

    // Circular buffer: store last 20 seconds of audio (pre-roll is 1.5s, 20s is more than sufficient)
    const val CIRCULAR_BUFFER_SECONDS = 20
    const val CIRCULAR_BUFFER_SAMPLES = SAMPLE_RATE * CIRCULAR_BUFFER_SECONDS

    // Speech segment extraction
    const val PRE_ROLL_SECONDS = 3.5f // Increased from 2.5s — 2.5s was clipping first word in idle mode
    const val POST_ROLL_SECONDS = 1.5f // Audio after speech stops

    // Dynamic Hysteresis Constants
    const val MIN_SPEECH_DURATION_ACTIVE_MS = 500L // Allows "yes/no" when conversing
    const val MIN_SPEECH_DURATION_IDLE_MS = 3000L // Rejects false-positive traffic when idle
    const val CONVERSATION_TIMEOUT_MS = 60000L // Timeout to revert back to IDLE state

    const val MAX_SPEECH_SEGMENT_SECONDS = 60 // Max single segment

    // Classification duty cycle
    const val CLASSIFICATION_INTERVAL_MS = 960L // ~1 WebRTC polling window
    const val IDLE_CLASSIFICATION_INTERVAL_MS = 3000L // 3× slower loop during extended silence
    const val IDLE_SLOWDOWN_AFTER_MS = 30_000L // 30 s without speech → switch to slow interval

    // Connectivity sync
    const val CONNECTIVITY_POLL_INTERVAL_MS = 120_000L // Check every 2 min (was 30s)
    const val HOURLY_SYNC_INTERVAL_MS = 3_600_000L     // Hourly fallback sync to phone
    const val PREF_LAST_SYNC_TIME     = "last_sync_time_ms"
    const val PREFS_NAME              = "omi4wos_wear_prefs"

    // Opus encoder parameters
    const val OPUS_BITRATE = 24000 // 24 kbps - good for speech
    const val OPUS_FRAME_SIZE_MS = 20 // 20ms frames
    const val OPUS_FRAME_SAMPLES = SAMPLE_RATE * OPUS_FRAME_SIZE_MS / 1000 // 320 samples

    // Data Layer chunk size
    const val MAX_DATA_LAYER_PAYLOAD = 100_000 // ~100KB per message

    // Omi API
    const val OMI_BASE_URL = "https://api.omi.me"
    const val OMI_CONVERSATIONS_PATH = "/v2/integrations/%s/user/conversations?uid=%s"

    // Notification
    const val WEAR_NOTIFICATION_CHANNEL_ID = "omi4wos_audio_capture"
    const val WEAR_NOTIFICATION_ID = 1001
    const val MOBILE_NOTIFICATION_CHANNEL_ID = "omi4wos_receiver"
    const val MOBILE_NOTIFICATION_ID = 1002

    // Preferences keys
    const val PREF_RECORDING_ENABLED = "recording_enabled"
    const val PREF_OMI_API_KEY = "omi_api_key"
    const val PREF_OMI_APP_ID = "omi_app_id"
    const val PREF_OMI_USER_ID = "omi_user_id"
}
