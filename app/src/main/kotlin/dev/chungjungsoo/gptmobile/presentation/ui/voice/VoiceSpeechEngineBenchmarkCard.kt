package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkFixture
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechEngineBenchmarkResult
import java.util.Locale

/** Engine output callback results deliberately omit native token timing and executed-processor claims. */
@Composable
internal fun VoiceSpeechEngineBenchmarkCard(result: VoiceSpeechEngineBenchmarkResult) {
    var expanded by remember(result.requestedBackend) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.requestedBackend.label, style = MaterialTheme.typography.titleSmall)
            Text(result.state.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${result.requestedDelegate} delegate requested · processor placement is not exposed by the SDK", style = MaterialTheme.typography.bodySmall)
            result.failureCode?.let { Text(it.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result.failedFixture?.let { Text("Stopped at ${it.label} · repeat ${result.failedRepeat ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
            result.timeoutScope?.let { Text("Deadline: ${it.lowercase()} · ${result.timeoutElapsedMs ?: "unknown"}/${result.timeoutLimitMs ?: "unknown"} ms", style = MaterialTheme.typography.bodySmall) }
            result.nativeRetirementMs?.let { Text("Native close duration: $it ms", style = MaterialTheme.typography.bodySmall) }
            Text("Cleanup: ${result.cleanupState.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            result.sessionPrepareMs?.let { Text("Session preparation: $it ms", style = MaterialTheme.typography.bodySmall) }
            for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                val statistics = result.fixtureStatistics(fixture)
                if (statistics.sampleCount == 0) continue
                Text("${fixture.label} · ${statistics.warmSampleCount} warm samples", style = MaterialTheme.typography.labelLarge)
                Text("Median ${statistics.medianWarmMs?.let(::formatEngineNumber) ?: "—"} ms · p95 ${statistics.p95WarmMs?.let { "$it ms" } ?: "needs 20 samples"} · RTF ${statistics.medianWarmRtf?.let(::formatEngineNumber) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                Text("First PCM median ${statistics.medianFirstPcmMs?.let(::formatEngineNumber) ?: "—"} ms", style = MaterialTheme.typography.bodySmall)
            }
            Text("Measured cost ${result.measuredCostMs} ms includes session preparation, completed fixtures and the failed attempt when present.", style = MaterialTheme.typography.bodySmall)
            result.partialSample?.let { Text("Partial ${it.fixture.label} retained: ${it.pcmChunks} PCM callbacks · ${formatEngineNumber(it.audioMs)} ms audio; completion is unverified.", style = MaterialTheme.typography.bodySmall) }
            Text("Structural PCM validation does not establish spoken-word accuracy or listening quality. No cross-engine winner is selected.", style = MaterialTheme.typography.bodySmall)
            if (result.samples.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide samples" else "Inspect callback timing") }
                if (expanded) {
                    result.samples.forEach { sample ->
                        Text("${sample.fixture.label} · ${if (sample.warm) "warm ${sample.repeat}" else "first use"}: first PCM ${sample.firstPcmMs ?: "—"} ms · total ${sample.generationMs} ms · ${sample.pcmChunks} callbacks · longest gap ${sample.maximumChunkGapMs ?: "—"} ms", style = MaterialTheme.typography.bodySmall)
                        Text("Audio ${formatEngineNumber(sample.audioMs)} ms · RTF ${sample.realTimeFactor?.let(::formatEngineNumber) ?: "—"} · thermal ${sample.thermalBefore ?: "unknown"} → ${sample.thermalAfter ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun formatEngineNumber(value: Double) = String.format(Locale.ROOT, "%.2f", value)
