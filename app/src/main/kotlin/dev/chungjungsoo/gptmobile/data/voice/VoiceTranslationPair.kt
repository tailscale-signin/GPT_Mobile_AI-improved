package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale
import kotlinx.serialization.Serializable

/** Saved language choices are preferences; only a validated pair can resolve an accepted turn. */
@Serializable
internal data class VoiceTranslationPair(
    val baseLanguage: String = "en-US",
    val translationLanguage: String = "fr-FR",
    val baseVoiceId: String = "azelma",
    val translationVoiceId: String = "estelle",
    val sourceLanguage: String? = null
) {
    fun validated(): VoiceTranslationPair? {
        val base = canonicalLanguage(baseLanguage) ?: return null
        val translation = canonicalLanguage(translationLanguage)?.takeUnless { it == base } ?: return null
        val baseVoice = baseVoiceId.trim().takeIf { it in profile(base).voices } ?: return null
        val translationVoice = translationVoiceId.trim().takeIf { it in profile(translation).voices } ?: return null
        val source = sourceLanguage?.let { canonicalLanguage(it)?.takeIf { tag -> tag == base || tag == translation } ?: return null }
        return copy(baseLanguage = base, translationLanguage = translation, baseVoiceId = baseVoice, translationVoiceId = translationVoice, sourceLanguage = source)
    }

    /** Restore usable preferences without turning corrupt or stale source choices into an explicit direction. */
    fun normalized(): VoiceTranslationPair {
        val savedBase = canonicalLanguage(baseLanguage)
        val savedTranslation = canonicalLanguage(translationLanguage)
        val base = savedBase ?: if (savedTranslation == "en-US") "fr-FR" else "en-US"
        val translation = savedTranslation?.takeUnless { it == base } ?: if (base == "fr-FR") "en-US" else "fr-FR"
        val baseVoice = baseVoiceId.trim().takeIf { it in profile(base).voices } ?: defaultVoice(base)
        val translationVoice = translationVoiceId.trim().takeIf { it in profile(translation).voices } ?: defaultVoice(translation)
        val source = canonicalLanguage(sourceLanguage)?.takeIf { it in listOf(savedBase, savedTranslation) && (it == base || it == translation) }
        return copy(baseLanguage = base, translationLanguage = translation, baseVoiceId = baseVoice, translationVoiceId = translationVoice, sourceLanguage = source)
    }

    /** An explicit source is the user's choice. Auto accepts only a detected language from this pair. */
    fun resolve(detectedLanguage: String?): VoiceTranslationTarget? {
        val pair = validated() ?: return null
        val source = pair.sourceLanguage ?: canonicalLanguage(detectedLanguage) ?: return null
        return when (source) {
            pair.baseLanguage -> VoiceTranslationTarget(source, pair.translationLanguage, pair.translationVoiceId)
            pair.translationLanguage -> VoiceTranslationTarget(source, pair.baseLanguage, pair.baseVoiceId)
            else -> null
        }
    }

    companion object {
        private val profiles get() = MediaPipeSpeechVoices.profiles(MediaPipeSpeechEngine.POCKET_MULTILINGUAL)

        /** Regional recognition tags identify a language family; the voice pack owns its canonical locale. */
        fun canonicalLanguage(tag: String?): String? {
            val value = tag?.trim()?.takeIf { it.isNotEmpty() && it.length <= 63 } ?: return null
            val language = try {
                Locale.Builder().setLanguageTag(value).build().language
            } catch (_: java.util.IllformedLocaleException) {
                return null
            }
            return profiles.firstOrNull { Locale.forLanguageTag(it.tag).language == language }?.tag
        }

        private fun profile(tag: String): SpeechLanguage = profiles.first { it.tag == tag }

        private fun defaultVoice(tag: String): String = if (tag == "en-US") "azelma" else profile(tag).voices.first()
    }
}

/** One draft's resolved direction and voice. Later preference changes cannot change this value. */
@Serializable
internal data class VoiceTranslationTarget(val sourceLanguage: String, val targetLanguage: String, val voiceId: String) {
    val responsePolicy: VoiceResponsePolicy get() = VoiceResponsePolicy.forLanguage(targetLanguage, translate = true)
}
