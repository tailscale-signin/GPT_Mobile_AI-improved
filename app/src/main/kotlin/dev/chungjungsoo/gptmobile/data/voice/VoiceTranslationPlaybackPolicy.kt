package dev.chungjungsoo.gptmobile.data.voice

/** Replay follows the accepted translation destination, never the last detected speaker. */
internal fun translationPlaybackTarget(policy: VoiceResponsePolicy?, pair: VoiceTranslationPair): VoiceTranslationTarget? {
    if (policy?.translationOnly != true) return null
    val selected = pair.validated() ?: return null
    return when (VoiceTranslationPair.canonicalLanguage(policy.targetLanguageTag)) {
        selected.baseLanguage -> VoiceTranslationTarget(selected.translationLanguage, selected.baseLanguage, selected.baseVoiceId)
        selected.translationLanguage -> VoiceTranslationTarget(selected.baseLanguage, selected.translationLanguage, selected.translationVoiceId)
        else -> null
    }
}
