package dev.chungjungsoo.gptmobile.data.voice

internal sealed interface VoiceTranslationPlaybackSelection {
    data object MissingPolicy : VoiceTranslationPlaybackSelection
    data object Ordinary : VoiceTranslationPlaybackSelection
    data object IncompatiblePair : VoiceTranslationPlaybackSelection
    data class Translated(val target: VoiceTranslationTarget) : VoiceTranslationPlaybackSelection
}

/**
 * An accepted reply owns its language independently of the mode used for future captures.
 * Null means the owning request metadata is unavailable; known ordinary replies use their
 * explicit ordinary policy. Supply a retained target only from the owning playback request.
 */
internal fun selectTranslationPlayback(
    policy: VoiceResponsePolicy?,
    pair: VoiceTranslationPair,
    retainedTarget: VoiceTranslationTarget? = null
): VoiceTranslationPlaybackSelection {
    if (policy == null) return VoiceTranslationPlaybackSelection.MissingPolicy
    if (!policy.translationOnly) return VoiceTranslationPlaybackSelection.Ordinary
    val acceptedLanguage = VoiceTranslationPair.canonicalLanguage(policy.targetLanguageTag)
    val retained = retainedTarget?.takeIf { target ->
        acceptedLanguage != null &&
            target.targetLanguage == acceptedLanguage &&
            target.sourceLanguage != acceptedLanguage &&
            VoiceTranslationPair.canonicalLanguage(target.sourceLanguage) == target.sourceLanguage &&
            MediaPipeSpeechVoices.profile(MediaPipeSpeechEngine.POCKET_MULTILINGUAL, target.voiceId)?.tag == acceptedLanguage
    }
    val target = retained ?: translationPlaybackTarget(policy, pair)
    return if (target == null) VoiceTranslationPlaybackSelection.IncompatiblePair else VoiceTranslationPlaybackSelection.Translated(target)
}

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
