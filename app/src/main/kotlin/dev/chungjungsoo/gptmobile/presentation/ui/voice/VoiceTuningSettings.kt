package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.voice.VoiceListeningStyle
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceHistory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import kotlinx.coroutines.flow.StateFlow

/** Separate small composables keep the voice settings coordinator below Android's method limit. */
@Composable
internal fun VoiceTuningSettings(
    value: VoiceTuningPreferences,
    change: (VoiceTuningPreferences) -> Unit,
    history: VoicePerformanceHistory,
    feedback: StateFlow<VoiceSynthesisFeedback>?,
    benchmark: (((String) -> Unit) -> Unit)?,
    adjustCaptionOffset: (Int) -> Unit,
    supportsCpuThreads: Boolean = true
) {
    VoiceSettingsSection("Listening response")
    VoiceChoice("Listening style", value.listeningStyle.name, VoiceListeningStyle.entries.map { it.name to it.label }, { change(value.copy(listeningStyle = VoiceListeningStyle.valueOf(it))) })
    Text("Quick responds sooner; Thoughtful allows longer pauses.", style = MaterialTheme.typography.bodySmall)
    VoiceChoice("Microphone batch", value.captureBatchMs.toString(), listOf(20, 40, 80, 100).map { it.toString() to "$it ms" }, { change(value.copy(captureBatchMs = it.toInt())) })
    VoiceSettingSwitch("Interrupt replies by speaking", value.handsFreeInterruption) { change(value.copy(handsFreeInterruption = it)) }
    Text("Hands-free interruption uses echo cancellation when the active audio route supports it.", style = MaterialTheme.typography.bodySmall)
    VoiceSettingsSection("Pronunciation")
    VoicePronunciationEditor(value.pronunciation) { change(value.copy(pronunciation = it)) }
    VoiceSettingsSection("Caption alignment")
    VoiceCaptionAdjustment(value.captionOffsetMs, feedback, adjustCaptionOffset)
    VoiceSettingsSection("Performance")
    if (supportsCpuThreads) VoiceCalibrationControl(benchmark)
    VoiceSynthesisStatus(feedback)
    VoicePerformancePanel(history, feedback?.collectAsStateWithLifecycle()?.value)
}

@Composable
private fun VoiceCaptionAdjustment(defaultOffset: Int, feedback: StateFlow<VoiceSynthesisFeedback>?, adjust: (Int) -> Unit) {
    val sample = feedback?.collectAsStateWithLifecycle()?.value
    val observed = sample?.takeIf { it.route != "unknown" }
    var offset by remember(observed?.route, observed?.captionOffsetMs, defaultOffset) { mutableIntStateOf(observed?.captionOffsetMs ?: defaultOffset) }
    Text("Caption adjustment · $offset ms")
    Slider(offset.toFloat(), {
        offset = (it / 10).toInt() * 10
        adjust(offset)
    }, valueRange = -500f..500f, steps = 99)
    observed?.let { Text("Saved for ${it.route}", style = MaterialTheme.typography.bodySmall) }
    Text("Positive values delay the highlight. Audio playback keeps its natural speed.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun VoicePronunciationEditor(value: Map<String, String>, save: (Map<String, String>) -> Unit) {
    var editor by remember(value) { mutableStateOf(value.entries.joinToString("\n") { "${it.key} = ${it.value}" }) }
    val parsed = remember(editor) { parsePronunciationEditor(editor) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = editor,
            onValueChange = { if (it.length <= 32_000) editor = it },
            modifier = Modifier.fillMaxWidth().testTag("voice_pronunciation_editor"),
            label = { Text("Word = spoken pronunciation") },
            supportingText = { Text("One entry per line. Example: Melo = mellow") },
            minLines = 2,
            maxLines = 6,
            isError = parsed == null
        )
        Button(onClick = { parsed?.let(save) }, enabled = parsed != null && parsed != value) { Text("Save pronunciations") }
    }
}

@Composable
private fun VoiceCalibrationControl(benchmark: (((String) -> Unit) -> Unit)?) {
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Button(onClick = {
        running = true
        result = "Measuring local synthesis…"
        benchmark?.invoke { message ->
            running = false
            result = message
        }
    }, enabled = benchmark != null && !running, modifier = Modifier.testTag("voice_cpu_benchmark")) {
        Text(if (running) "Measuring…" else "Measure CPU setting for Auto")
    }
    Text("Auto performance uses the measured CPU choice and adapts to device heat.", style = MaterialTheme.typography.bodySmall)
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
