package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguage
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationTarget

internal data class VoiceTranslationPlayback(val target: VoiceTranslationTarget, val factory: VoicePhraseEngineFactory)

internal data class VoicePlaybackSelection(
    val factory: VoicePhraseEngineFactory,
    val profile: SpeechLanguage,
    val requireLocalOutput: Boolean
)

/** An accepted output survives presentation restoration until its owner is explicitly cleared. */
internal fun selectVoicePlayback(
    ordinaryFactory: VoicePhraseEngineFactory,
    ordinaryProfile: SpeechLanguage,
    retainedTranslation: VoiceTranslationPlayback?
): VoicePlaybackSelection = VoicePlaybackSelection(
    factory = retainedTranslation?.factory ?: ordinaryFactory,
    profile = retainedTranslation?.let { MediaPipeSpeechVoices.forVoice(it.target.voiceId) } ?: ordinaryProfile,
    requireLocalOutput = retainedTranslation != null
)
