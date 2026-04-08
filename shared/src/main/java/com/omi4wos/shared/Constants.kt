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

    // WebRTC VAD parameters
    const val WEBRTC_INPUT_SAMPLES = 15360 // 0.960s at 16kHz (exactly 48 frames of 20ms)
    const val LOUDNESS_THRESHOLD_DB = 45.0

    // Circular buffer: store last 30 seconds of audio
    const val CIRCULAR_BUFFER_SECONDS = 30
    const val CIRCULAR_BUFFER_SAMPLES = SAMPLE_RATE * CIRCULAR_BUFFER_SECONDS

    // Speech segment extraction
    const val PRE_ROLL_SECONDS = 2.5f // Audio before speech detection
    const val POST_ROLL_SECONDS = 1.5f // Audio after speech stops
    const val MIN_SPEECH_DURATION_MS = 500L // Minimum speech segment length
    const val MAX_SPEECH_SEGMENT_SECONDS = 60 // Max single segment

    // Classification duty cycle
    const val CLASSIFICATION_INTERVAL_MS = 960L // ~1 WebRTC polling window

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
