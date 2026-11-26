package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginAudio
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationDictionary
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession

/** Token/caption recompositions never rewrite route calibration or rebuild the pronunciation map. */
internal class VoiceTuningConfiguration(
    private val audio: VoicePluginAudio,
    private val output: KokoroOutput?,
    private val session: VoiceSession
) {
    private var previous: VoiceTuningPreferences? = null

    private var previousLanguage: String? = null

    fun apply(value: VoiceTuningPreferences, language: String) {
        val before = previous
        if (before == value && previousLanguage == language) return
        if (before?.listeningStyle != value.listeningStyle) audio.listeningStyle = value.listeningStyle
        if (before?.captureBatchMs != value.captureBatchMs) audio.captureBatchMs = value.captureBatchMs
        if (before?.handsFreeInterruption != value.handsFreeInterruption) session.configureInterruption(value.handsFreeInterruption)
        if (previousLanguage != language || before?.dictionary(language) != value.dictionary(language)) {
            val entries = value.dictionary(language)
            session.pronunciationDictionary = VoicePronunciationDictionary(entries, language)
            audio.configurePronunciation(entries, language)
            previousLanguage = language
        }
        if (before?.captionOffsetMs != value.captionOffsetMs) output?.configureCaptionOffset(value.captionOffsetMs)
        previous = value
    }
}
