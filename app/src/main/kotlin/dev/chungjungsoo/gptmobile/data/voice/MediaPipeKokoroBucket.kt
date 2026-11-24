package dev.chungjungsoo.gptmobile.data.voice

/** Published Tasks Audio 1.2.3 capacities. AUTO delegates shape selection to the model. */
enum class MediaPipeKokoroBucket(val label: String, val sdkValue: Int) {
    AUTO("Model default", 0),
    TOKENS_64("64 tokens", 64),
    TOKENS_128("128 tokens", 128),
    TOKENS_256("256 tokens", 256);

    companion object {
        fun fromSdkValue(value: Int): MediaPipeKokoroBucket = entries.singleOrNull { it.sdkValue == value }
            ?: throw IllegalArgumentException("Unsupported Kokoro sequence bucket")
    }
}

/** Incremental replies have bounded metadata/timelines; they never retain complete PCM. */
internal object VoiceIncrementalStreamLimits {
    const val MAX_INPUT_BYTES = 96_000
    const val MAX_SOURCE_CHARACTERS = 12_100
    const val MAX_SEGMENTS = MAX_SOURCE_CHARACTERS
    const val MAX_CUES = 16_384
    const val MAX_FRAMES = VOICE_PCM_SAMPLE_RATE * 1_800L
}
