package com.omi4wos.shared

/**
 * Paths and keys used for Wear Data Layer communication between watch and phone.
 */
object DataLayerPaths {
    // MessageClient paths
    const val AUDIO_SPEECH_PATH = "/audio/speech"
    const val AUDIO_CONTROL_PATH = "/audio/control"
    const val STATUS_PATH = "/status"
    const val CONFIG_PATH = "/config"

    // ChannelClient path for streaming large audio
    const val AUDIO_STREAM_PATH = "/audio/stream"

    // DataMap keys for audio messages
    const val KEY_AUDIO_DATA = "audio_data"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_CHUNK_INDEX = "chunk_index"
    const val KEY_SEGMENT_ID = "segment_id"
    const val KEY_IS_FINAL = "is_final"
    const val KEY_SAMPLE_RATE = "sample_rate"
    const val KEY_CODEC = "codec"

    // DataMap keys for control messages
    const val KEY_COMMAND = "command"
    const val KEY_IS_RECORDING = "is_recording"
    const val KEY_SPEECH_DETECTED = "speech_detected"
    const val KEY_ERROR = "error"

    // Control commands
    const val CMD_START_RECORDING = "start"
    const val CMD_STOP_RECORDING = "stop"
    const val CMD_STATUS_REQUEST = "status_request"
    const val CMD_STATUS_RESPONSE = "status_response"

    // Codec identifiers
    const val CODEC_OPUS = "opus"
    const val CODEC_PCM16 = "pcm16"

    // Capability names for node discovery
    const val CAPABILITY_PHONE_APP = "omi4wos_phone"
    const val CAPABILITY_WEAR_APP = "omi4wos_wear"
}
