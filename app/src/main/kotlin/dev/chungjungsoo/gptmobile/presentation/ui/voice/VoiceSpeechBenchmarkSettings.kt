package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBackendResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBackendState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkFixture
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkIssue
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkMode
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport
import dev.chungjungsoo.gptmobile.data.voice.voiceSpeechBenchmarkExport
import dev.melo.kokoro.TimedKokoro
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceSpeechBenchmarkSettings(
    voice: KokoroVoice?,
    threads: Int,
    speechRate: Float,
    enabled: Boolean,
    beforeStart: () -> Unit = {},
    viewModel: VoiceSpeechBenchmarkViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(VoiceSpeechBenchmarkMode.QUICK.name) }
    var selected by rememberSaveable { mutableStateOf(VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS.map { it.name }) }
    VoiceSettingsSection("Speech benchmark")
    Text("Compare CPU, GPU and NPU with the same short, medium and long samples. Runs silently on this phone and saves the last five results.", style = MaterialTheme.typography.bodySmall)
    VoiceChoice("Test length", mode, VoiceSpeechBenchmarkMode.entries.map { it.name to "${it.label} · ${it.warmRepeats} warm runs per sample" }, { mode = it }, enabled = enabled && !state.running)
    for (backend in VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = backend.name in selected,
                onCheckedChange = { checked -> selected = if (checked) (selected + backend.name).distinct() else selected - backend.name },
                enabled = enabled && !state.running,
                modifier = Modifier.testTag("voice_speech_benchmark_${backend.name.lowercase()}")
            )
            Text(backend.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        if (voice == null) "Select a Kokoro voice to run the benchmark." else "${voice.label} · $threads CPU threads · ${format(speechRate.toDouble())}× speech rate",
        style = MaterialTheme.typography.bodySmall
    )
    Button(
        onClick = {
            if (state.running) {
                viewModel.cancel()
            } else if (voice != null) {
                beforeStart()
                viewModel.start(VoiceSpeechBenchmarkMode.valueOf(mode), VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS.filter { it.name in selected }, voice, threads, speechRate)
            }
        },
        enabled = if (state.running) !state.cancelling else enabled && state.loaded && voice != null && selected.isNotEmpty(),
        modifier = Modifier.testTag("voice_speech_benchmark_run")
    ) {
        Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
        Text(
            if (state.cancelling) {
                "Stopping benchmark…"
            } else if (state.running) {
                "Stop benchmark"
            } else {
                "Run ${mode.lowercase()} benchmark"
            },
            Modifier.padding(start = 8.dp)
        )
    }
    if (state.running) {
        val report = state.report
        LinearProgressIndicator(progress = { report?.let { it.completedSamples.toFloat() / it.totalSamples.coerceAtLeast(1) } ?: 0f }, modifier = Modifier.fillMaxWidth())
        val active = report?.results?.firstOrNull { it.state in setOf(VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING) }
        Text(
            listOfNotNull(active?.requestedBackend?.label, report?.activeFixture?.label, report?.activeRepeat?.let { if (it == 0) "first use" else "warm run $it" }).joinToString(" · ").ifBlank { "Checking installed voice model…" },
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (state.history.size > 1 && !state.running) {
        val current = state.history.indexOfFirst { it.startedAtMillis == state.report?.startedAtMillis }.coerceAtLeast(0)
        VoiceChoice(
            "Saved results",
            current.toString(),
            state.history.mapIndexed { index, report -> index.toString() to "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.startedAtMillis))} · ${report.configuration.mode.label}" },
            { it.toIntOrNull()?.let(viewModel::selectHistory) }
        )
    }
    state.report?.let { report ->
        val recorded = report.configuration
        if (!state.running &&
            (
                recorded.voice != voice ||
                    recorded.threads != threads ||
                    recorded.speechRate != speechRate ||
                    recorded.appBuild != BuildConfig.VERSION_CODE ||
                    recorded.modelSha256 != KokoroModelPack.MODEL_SHA256 ||
                    recorded.runtimeId != TimedKokoro.RUNTIME_ID
                )
        ) {
            Text("These saved results used different settings or an earlier app/runtime. Run again to compare your current setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        VoiceSpeechBenchmarkResults(report)
        if (!state.running) VoiceSpeechBenchmarkExportControl(report)
    }
    if (!state.saved) Text("Results are visible, but could not be saved on this phone. Export them before leaving.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun VoiceSpeechBenchmarkResults(report: VoiceSpeechBenchmarkReport) {
    Text("${report.configuration.mode.label} · ${report.state.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.titleSmall)
    Text(
        "${report.configuration.voice.label} · ${report.configuration.threads} threads · ${format(report.configuration.speechRate.toDouble())}× · build ${report.configuration.appBuild}",
        style = MaterialTheme.typography.bodySmall
    )
    val notice = when (report.issue) {
        VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE -> "Install or repair the Kokoro model in Voice settings before testing."
        VoiceSpeechBenchmarkIssue.THERMAL_LIMIT -> "Stopped because the phone became warm. Let it cool before comparing engines. Completed measurements are retained."
        VoiceSpeechBenchmarkIssue.EXECUTION_FAILED -> "The benchmark could not finish. Completed measurements are retained."
        VoiceSpeechBenchmarkIssue.CANCELLED -> "Stopped at your request. Unfinished backends have no performance result."
        null -> null
    }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    report.recommendation()?.let { Text("Recommendation for this test: ${it.label}. An accelerator needs at least a ten percent gain across this workload, including preparation.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
    for (result in report.results) VoiceSpeechBenchmarkResultCard(result)
    Text(
        "First PCM means the complete phrase became available. Playback, speaker latency, underruns and battery energy are not measured by this silent test. Cold means a fresh engine/phrase shape; the phone's file cache is retained. Percentiles describe these samples, not a long-term guarantee.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VoiceSpeechBenchmarkResultCard(result: VoiceSpeechBenchmarkBackendResult) {
    var expanded by remember(result.requestedBackend) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.requestedBackend.label, style = MaterialTheme.typography.titleSmall)
            Text(result.state.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            result.failure?.let { failure ->
                Text("${failure.name.lowercase().replace('_', ' ')}${result.failureStage?.let { " · ${it.name.lowercase()}" }.orEmpty()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                result.failureEffectiveProvider?.let { Text("Observed provider: $it. This is not a successful ${result.requestedBackend.label} measurement.", style = MaterialTheme.typography.bodySmall) }
            }
            result.sessionPrepareMs?.let { Text("Fresh session: $it ms", style = MaterialTheme.typography.bodySmall) }
            result.samples.firstOrNull()?.prepareToFirstPcmMs?.let { Text("Prepare to first PCM: $it ms", style = MaterialTheme.typography.bodySmall) }
            if (result.samples.isNotEmpty()) {
                Text("Warm p50: ${result.medianMs ?: "—"} ms · p95: ${result.p95Ms ?: "—"} ms · ${result.warmSamples.size} samples", style = MaterialTheme.typography.bodySmall)
                Text("Median warm RTF: ${result.medianRtf?.let(::format) ?: "—"} · under 1.00 generates faster than playback", style = MaterialTheme.typography.bodySmall)
                val latest = result.samples.last()
                Text("Observed: ${latest.effectiveProvider}${if (latest.acceleratorKernels > 0 && latest.cpuKernels > 0) " + CPU" else ""}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (latest.acceleratorKernels + latest.cpuKernels > 0) "First-shape profile: ${latest.acceleratorKernels} accelerator / ${latest.cpuKernels} CPU kernel events" else "Kernel profile not collected for this provider",
                    style = MaterialTheme.typography.bodySmall
                )
                if (latest.acceleratorKernels + latest.cpuKernels > 0) {
                    Text("Profiled kernel time: ${format(latest.acceleratorMicros / 1000.0)} ms accelerator · ${format(latest.cpuMicros / 1000.0)} ms CPU", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide samples" else "View all samples") }
                if (expanded) {
                    for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                        val rows = result.samples.filter { it.fixture == fixture }
                        if (rows.isNotEmpty()) {
                            Text(fixture.label, style = MaterialTheme.typography.labelLarge)
                            for (sample in rows) {
                                Text(
                                    "${if (sample.warm) "Warm ${sample.repeat}" else "First shape"}: ${sample.firstPcmMs} ms · ${format(sample.audioMs / 1000)} s audio · RTF ${sample.realTimeFactor?.let(::format) ?: "—"} · thermal ${sample.thermalBefore ?: "—"} → ${sample.thermalAfter ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSpeechBenchmarkExportControl(report: VoiceSpeechBenchmarkReport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captured by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
        val frozen = captured
        captured = null
        if (destination != null && frozen != null) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination)?.bufferedWriter()?.use { it.write(frozen) }
                            ?: error("Destination unavailable")
                    }
                    notice = "Speech benchmark exported."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = "Export failed. Choose another destination."
                } finally {
                    saving = false
                }
            }
        }
    }
    TextButton(
        onClick = {
            captured = voiceSpeechBenchmarkExport(report)
            notice = null
            export.launch("speech-benchmark-${report.startedAtMillis}.json")
        },
        enabled = !saving && captured == null,
        modifier = Modifier.testTag("voice_speech_benchmark_export")
    ) { Text(if (saving) "Saving…" else "Export benchmark results") }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

private fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)
