package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale

internal data class SpeechLanguage(val tag: String, val preview: String, val voices: List<String>) {
    val dictionaryLanguage: String get() = tag.substringBefore('-')
    val label: String get() = Locale.forLanguageTag(tag).displayName
}

/** Voice IDs from the pinned LiteRT Kokoro bundle. The runtime checks its own catalog before playback. */
internal object SpeechLanguages {
    val synthesis = listOf(
        SpeechLanguage("en-US", "Hello. This is your selected voice. I am ready when you are.", "af_heart af_alloy af_aoede af_bella af_jessica af_kore af_nicole af_nova af_river af_sarah af_sky am_adam am_echo am_eric am_fenrir am_liam am_michael am_onyx am_puck am_santa".split(" ")),
        SpeechLanguage("en-GB", "Hello. This is your selected voice. I am ready when you are.", "bf_emma bf_alice bf_isabella bf_lily bm_daniel bm_fable bm_george bm_lewis".split(" ")),
        SpeechLanguage("es", "Hola. Esta es la voz que has elegido.", "ef_dora em_alex em_santa".split(" ")),
        SpeechLanguage("fr", "Bonjour. Voici la voix que vous avez choisie.", "ff_siwis".split(" ")),
        SpeechLanguage("it", "Ciao. Questa è la voce che hai scelto.", "if_sara im_nicola".split(" ")),
        SpeechLanguage("pt-BR", "Olá. Esta é a voz que você escolheu.", "pf_dora pm_alex pm_santa".split(" ")),
        SpeechLanguage("hi", "नमस्ते। यह आपकी चुनी हुई आवाज़ है।", "hf_alpha hf_beta hm_omega hm_psi".split(" ")),
        SpeechLanguage("zh", "你好，这是你选择的声音。", "zf_xiaobei zf_xiaoni zf_xiaoxiao zf_xiaoyi zm_yunjian zm_yunxi zm_yunxia zm_yunyang".split(" ")),
        SpeechLanguage("ja", "こんにちは。これが選択した音声です。", "jf_alpha jf_gongitsune jf_nezumi jf_tebukuro jm_kumo".split(" "))
    )
    val default = synthesis.first()

    fun forVoice(id: String): SpeechLanguage? = synthesis.firstOrNull { id in it.voices }

    fun forLocale(tag: String): SpeechLanguage? {
        val locale = Locale.forLanguageTag(tag)
        return synthesis.firstOrNull { it.tag.equals(locale.toLanguageTag(), ignoreCase = true) }
            ?: synthesis.firstOrNull { Locale.forLanguageTag(it.tag).language == locale.language }
    }

    fun availableVoice(requested: String, available: Set<String>, sameLanguageFallback: Boolean): String? {
        if (requested in available) return requested
        if (!sameLanguageFallback) return null
        return synthesis.firstOrNull { requested in it.voices }?.voices?.firstOrNull { it in available }
    }

    fun recognitionDefault(locale: Locale = Locale.getDefault()): String =
        locale.toLanguageTag().takeIf { locale.language.isNotEmpty() && it != "und" } ?: default.tag

    fun recognitionBackend(tag: String, englishBackend: VoiceRecognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE) = if (Locale.forLanguageTag(tag).language == "en") {
        englishBackend
    } else {
        VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
    }
}

internal data class VoiceSynthesisPreferences(val language: String = "en-US", val voices: Map<String, String> = emptyMap()) {
    val profile: SpeechLanguage get() = SpeechLanguages.synthesis.firstOrNull { it.tag == language } ?: SpeechLanguages.default
    val voiceId: String get() = voices[profile.tag]?.takeIf { it in profile.voices } ?: profile.voices.first()

    fun selectLanguage(tag: String) = SpeechLanguages.forLocale(tag)?.let { copy(language = it.tag) } ?: this

    fun selectVoice(id: String) = if (id in profile.voices) copy(voices = voices + (profile.tag to id)) else this

    /** Restore only IDs usable by their own language. Old installations keep their selected English voice. */
    companion object {
        fun restore(tag: String?, saved: Set<*>?, legacyVoiceId: String?, defaultTag: String = "en-US"): VoiceSynthesisPreferences {
            val legacy = SpeechLanguages.synthesis.firstOrNull { legacyVoiceId in it.voices }
            val valid = buildMap {
                if (saved == null && legacy != null && legacyVoiceId != null) put(legacy.tag, legacyVoiceId)
                saved?.filterIsInstance<String>()?.forEach { entry ->
                    val lang = entry.substringBefore('=')
                    val id = entry.substringAfter('=', "")
                    if (SpeechLanguages.synthesis.any { it.tag == lang && id in it.voices }) put(lang, id)
                }
            }
            return VoiceSynthesisPreferences(legacy?.tag ?: SpeechLanguages.forLocale(defaultTag)?.tag ?: SpeechLanguages.default.tag, valid).selectLanguage(tag.orEmpty())
        }
    }
}
