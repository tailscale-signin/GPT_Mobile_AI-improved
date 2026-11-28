package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguage
import dev.chungjungsoo.gptmobile.data.voice.VoiceListeningStyle
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceHistory
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationLocales
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import kotlinx.coroutines.flow.StateFlow

/** Separate small composables keep the voice settings coordinator below Android's method limit. */
@Composable
internal fun VoiceTuningSettings(
    value: VoiceTuningPreferences,
    change: (VoiceTuningPreferences) -> Unit,
    history: VoicePerformanceHistory,
    feedback: StateFlow<VoiceSynthesisFeedback>?,
    adjustCaptionOffset: (Int) -> Unit,
    language: SpeechLanguage
) {
    VoiceSettingsSection("Conversation pacing")
    VoiceSettingSwitch("Speak a brief acknowledgment while thinking", value.spokenAcknowledgments) { change(value.copy(spokenAcknowledgments = it)) }
    VoiceSettingsSection("Listening response")
    VoiceChoice("Listening style", value.listeningStyle.name, VoiceListeningStyle.entries.map { it.name to it.label }, { change(value.copy(listeningStyle = VoiceListeningStyle.valueOf(it))) })
    Text("Quick responds sooner; Thoughtful allows longer pauses.", style = MaterialTheme.typography.bodySmall)
    VoiceChoice("Microphone batch", value.captureBatchMs.toString(), listOf(20, 40, 80, 100).map { it.toString() to "$it ms" }, { change(value.copy(captureBatchMs = it.toInt())) })
    VoiceSettingSwitch("Interrupt replies by speaking", value.handsFreeInterruption) { change(value.copy(handsFreeInterruption = it)) }
    Text("Hands-free interruption uses echo cancellation when the active audio route supports it.", style = MaterialTheme.typography.bodySmall)
    VoiceSettingsSection("Pronunciation")
    val dictionaryLanguage = value.pronunciationLanguage(language.dictionaryLanguage)
    val dictionaryChoices = VoicePronunciationLocales.choices(language.dictionaryLanguage)
    if (dictionaryChoices.size > 1) {
        VoiceChoice("Pronunciation dictionary", dictionaryLanguage, dictionaryChoices, { selected ->
            change(value.copy(pronunciationLocales = value.pronunciationLocales + (language.dictionaryLanguage to selected)))
        })
        Text("Regional dictionaries add your local names and spellings. Shared corrections still apply. The selected voice determines the spoken accent.", style = MaterialTheme.typography.bodySmall)
    }
    VoicePronunciationEditor(dictionaryLanguage, value.editableDictionary(language.dictionaryLanguage), value.inheritedDictionary(language.dictionaryLanguage)) {
        change(value.withDictionary(language.dictionaryLanguage, it))
    }
    VoiceSettingsSection("Caption alignment")
    VoiceCaptionAdjustment(value.captionOffsetMs, feedback, adjustCaptionOffset)
    VoiceSettingsSection("Performance")
    VoiceSynthesisStatus(feedback)
    VoicePerformancePanel(history, feedback?.collectAsStateWithLifecycle()?.value)
}

@Composable
private fun VoiceCaptionAdjustment(defaultOffset: Int, feedback: StateFlow<VoiceSynthesisFeedback>?, adjust: (Int) -> Unit) {
    val sample = feedback?.collectAsStateWithLifecycle()?.value
    val observed = sample?.takeIf { it.route != "unknown" && it.bufferState != "idle" }
    var offset by remember(observed?.route, observed?.captionOffsetMs, defaultOffset) { mutableIntStateOf(observed?.captionOffsetMs ?: defaultOffset) }
    Text("Caption adjustment · $offset ms")
    Slider(offset.toFloat(), {
        offset = (it / 10).toInt() * 10
        adjust(offset)
    }, valueRange = -500f..500f, steps = 99)
    Text(if (observed == null) "Changes apply to the next playback device." else "Saved for the playing ${observed.route} device.", style = MaterialTheme.typography.bodySmall)
    Text("Positive values delay the highlight. Audio playback keeps its natural speed.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun VoiceSynthesisStatus(feedback: StateFlow<VoiceSynthesisFeedback>?) {
    val sample = feedback?.collectAsStateWithLifecycle()?.value ?: return
    if (sample.generatedAudioMs <= 0) return
    val threads = if (sample.executionProvider.startsWith("mediapipe-")) "runtime-managed threads" else "${sample.threads} threads"
    Text("${sample.executionProvider} · $threads · ${if (sample.warm) "warm" else "cold"}", style = MaterialTheme.typography.labelLarge)
    sample.executionEvidence?.let { evidence ->
        Text("Selected ${evidence.selectedBackend.label} · ${if (evidence.verifiedAudio) "audio and timing validated" else "validation pending"}", style = MaterialTheme.typography.bodySmall)
        if (evidence.profileAvailable == true) {
            Text("${evidence.acceleratorKernels} accelerator / ${evidence.cpuKernels} CPU kernel events", style = MaterialTheme.typography.bodySmall)
            Text("Shape preparation ${evidence.prepareMs} ms · profile ${evidence.profileId ?: "—"}${if (evidence.profileReused == true) " reused from this shape's first inference" else " from this shape's first inference"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Kernel profile ${if (evidence.profileAvailable == false) "not collected" else "unavailable"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        evidence.currentPrepareMs?.let { Text("Current preparation $it ms · ${evidence.cachedShapeCount ?: "—"}/${evidence.cacheLimit ?: "—"} exact shapes retained", style = MaterialTheme.typography.bodySmall) }
    }
    Text("${sample.synthesisMs} ms synthesis / ${sample.generatedAudioMs} ms audio · ${sample.bufferedAudioMs} ms queued · ${sample.underruns} underruns", style = MaterialTheme.typography.bodySmall)
    Text("${sample.bufferMs} ms reserve · ${sample.bufferState}", style = MaterialTheme.typography.bodySmall)
    if (sample.productionRatio?.let { it >= 1.0 } == true) {
        Text("Speech is being prepared more slowly than it plays. A larger reserve helps; a faster verified backend can reduce pauses.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (sample.engineReused && !sample.warmAtRequest) {
        Text("Reused the model prepared while this reply was waiting.", style = MaterialTheme.typography.bodySmall)
    }
    Text("${sample.route} · ${sample.clockBasis}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    sample.cancellationMs?.let { Text("Last cancellation: $it ms", style = MaterialTheme.typography.bodySmall) }
}
