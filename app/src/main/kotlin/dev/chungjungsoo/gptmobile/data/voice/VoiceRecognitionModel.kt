package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.JNI
import java.util.Locale

/** Engine choice and execution placement have independent persisted identities. */
enum class VoiceRecognitionEngine { MOONSHINE, SHERPA }

enum class VoiceRecognitionCompute(val title: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("Qualcomm NPU");

    companion object {
        fun fromSaved(value: String?): VoiceRecognitionCompute = entries.firstOrNull { it.name == value } ?: CPU
    }
}

/** Native timing describes model output; it does not certify audible word alignment. */
enum class VoiceRecognitionTimingCapability { SEGMENT, TOKEN, WORD }

enum class VoiceRecognitionModel(
    val id: String,
    val title: String,
    val engine: VoiceRecognitionEngine,
    val supportedLanguages: Set<String>,
    val nativeArchitecture: Int?,
    val timingCapability: VoiceRecognitionTimingCapability
) {
    MOONSHINE_TINY(
        "moonshine-tiny",
        "Moonshine Tiny Streaming",
        VoiceRecognitionEngine.MOONSHINE,
        setOf("en"),
        JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
        VoiceRecognitionTimingCapability.SEGMENT
    ),
    MOONSHINE_SMALL(
        "moonshine-small",
        "Moonshine Small Streaming",
        VoiceRecognitionEngine.MOONSHINE,
        setOf("en"),
        JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
        VoiceRecognitionTimingCapability.WORD
    ),
    MOONSHINE_MEDIUM(
        "moonshine-medium",
        "Moonshine Medium Streaming",
        VoiceRecognitionEngine.MOONSHINE,
        setOf("en"),
        JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,
        VoiceRecognitionTimingCapability.WORD
    ),
    ZIPFORMER_EN(
        "zipformer-en",
        "Zipformer Streaming · English",
        VoiceRecognitionEngine.SHERPA,
        setOf("en"),
        null,
        VoiceRecognitionTimingCapability.TOKEN
    ),
    ZIPFORMER_FR(
        "zipformer-fr",
        "Zipformer Streaming · French",
        VoiceRecognitionEngine.SHERPA,
        setOf("fr"),
        null,
        VoiceRecognitionTimingCapability.TOKEN
    ),
    NEMOTRON_3_5(
        "nemotron-3.5",
        "Nemotron 3.5 ASR Streaming 0.6B",
        VoiceRecognitionEngine.SHERPA,
        setOf(
            "ar", "bg", "cs", "da", "de", "en", "es", "et", "fi", "fr", "hi", "hr", "hu", "it",
            "ja", "ko", "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sv", "tr", "uk", "vi", "zh"
        ),
        null,
        VoiceRecognitionTimingCapability.TOKEN
    );

    fun supportsLanguage(tag: String): Boolean = Locale.forLanguageTag(tag.replace('_', '-')).language in supportedLanguages

    companion object {
        fun fromSaved(value: String?): VoiceRecognitionModel = entries.firstOrNull { it.id == value || it.name == value } ?: MOONSHINE_TINY
    }
}
