package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguage
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationTarget

internal data class VoiceTranslationPlayback(val target: VoiceTranslationTarget, val factory: VoicePhraseEngineFactory)

/** These are the settings captured when the multilingual factory is created. */
internal data class VoiceTranslationFactorySettings(val rate: Float, val bucket: MediaPipeKokoroBucket, val incrementalInput: Boolean)

/** Call only after an explicit settings action has stopped playback, never during preference hydration. */
internal fun translationPlaybackAfterSettingsChange(
    retained: VoiceTranslationPlayback?,
    previous: VoiceTranslationFactorySettings,
    next: VoiceTranslationFactorySettings
): VoiceTranslationPlayback? = retained.takeIf { previous == next }

internal data class VoiceTranslationFactoryPreparation(
    val generation: Int,
    val configuration: VoiceTranslationFactorySettings,
    val pair: VoiceTranslationPair
)

/** A preparation may publish only to the cache generation and settings that requested it. */
internal data class VoiceTranslationFactoryCache(
    val generation: Int = 0,
    val factories: Map<String, VoicePhraseEngineFactory> = emptyMap()
) {
    operator fun get(key: String): VoicePhraseEngineFactory? = factories[key]

    fun cleared(): VoiceTranslationFactoryCache = copy(factories = emptyMap())

    fun afterSettingsChange(previous: VoiceTranslationFactorySettings, next: VoiceTranslationFactorySettings): VoiceTranslationFactoryCache =
        if (previous == next) this else VoiceTranslationFactoryCache(generation + 1)

    fun preparation(configuration: VoiceTranslationFactorySettings, pair: VoiceTranslationPair): VoiceTranslationFactoryPreparation =
        VoiceTranslationFactoryPreparation(generation, configuration, pair)

    fun publish(
        request: VoiceTranslationFactoryPreparation,
        configuration: VoiceTranslationFactorySettings,
        pair: VoiceTranslationPair?,
        prepared: Map<String, VoicePhraseEngineFactory>
    ): VoiceTranslationFactoryCache =
        if (request.generation == generation && request.configuration == configuration && request.pair == pair) copy(factories = prepared) else this
}

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
