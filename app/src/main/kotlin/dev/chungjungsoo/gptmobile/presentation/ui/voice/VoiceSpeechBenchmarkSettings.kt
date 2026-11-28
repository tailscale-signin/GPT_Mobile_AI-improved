package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchStatus
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceSnapshot
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechAudioReferenceStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkAudioReference
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBackendResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkEnergyObservation
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkEngine
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkFixture
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkIssue
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkMode
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkTargets
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechEnergyStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackScenario
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechRejectedOutput
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import dev.chungjungsoo.gptmobile.data.voice.VoiceTempoProcessor
import dev.chungjungsoo.gptmobile.data.voice.measuredStreamingCandidate
import dev.chungjungsoo.gptmobile.data.voice.mediaPipeEngine
import dev.chungjungsoo.gptmobile.data.voice.streamingComparisons
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportContent
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun VoiceSpeechBenchmarkSettings(
    voice: KokoroVoice?,
    threads: Int,
    speechRate: Float,
    enabled: Boolean,
    beforeStart: suspend () -> Unit = {},
    captionOffsetMs: Int = 0,
    viewModel: VoiceSpeechBenchmarkViewModel = hiltViewModel(),
    streamingKokoroVoice: String? = null,
    performance: () -> VoicePerformanceSnapshot = { VoicePerformanceSnapshot() },
    feedback: () -> VoiceSynthesisFeedback? = { null },
    gateway: VoicePhraseEngineFactory? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val suite = state.suite
    VoiceSettingsSection("Voice benchmark")
    Text("The full benchmark briefly samples the microphone, plays test speech, and checks recognition, voices, captions and each installed processor route. A selected CUDA Gateway receives only fixed test phrases. Sustained tests run for 1, 5 and 10 minutes per route. Quick engine screening runs before the longer comparisons. A fully equipped phone can take hours. Successful CPU calibration updates Auto threads. You can stop, export, and continue a compatible local campaign.", style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            if (state.running) {
                viewModel.cancel()
            } else {
                viewModel.startFull(voice ?: KokoroVoice.HEART, threads, speechRate, captionOffsetMs, streamingKokoroVoice, performance(), feedback(), gateway, beforeStart)
            }
        },
        enabled = if (state.running) !state.cancelling else enabled && state.loaded && !state.nativeCleanupPending,
        modifier = Modifier.testTag("voice_speech_benchmark_run")
    ) {
        Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
        Text(
            if (state.cancelling) {
                "Stopping benchmark…"
            } else if (state.running) {
                "Stop benchmark"
            } else {
                "Run full voice benchmark"
            },
            Modifier.padding(start = 8.dp)
        )
    }
    if (!state.running &&
        suite?.hasUnfinishedStages == true &&
        suite.continuationSignature != null &&
        suite.state in setOf("CANCELLED", "INTERRUPTED", "THERMAL_LIMIT") &&
        gateway == null
    ) {
        TextButton(
            onClick = {
                viewModel.startFull(voice ?: KokoroVoice.HEART, threads, speechRate, captionOffsetMs, streamingKokoroVoice, performance(), feedback(), gateway, beforeStart, resume = true)
            },
            enabled = enabled && state.loaded && !state.nativeCleanupPending,
            modifier = Modifier.testTag("voice_speech_benchmark_resume")
        ) { Text("Continue unfinished benchmark") }
    }
    if (state.running && suite == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Checking installed models…", style = MaterialTheme.typography.bodySmall)
    }
    if (suite != null) {
        val active = suite.results.firstOrNull { it.status == VoiceBenchStatus.RUNNING }
        val completed = suite.completed
        Text("${suite.state.lowercase(Locale.ROOT).replace('_', ' ')} · $completed of ${suite.results.size} stages recorded", style = MaterialTheme.typography.titleSmall)
        if (state.running) {
            LinearProgressIndicator(progress = { completed.toFloat() / suite.results.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            active?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium) }
            state.report?.let { report ->
                val fixture = report.activeFixture?.label
                Text(listOfNotNull(fixture, "${report.completedSamples} samples recorded").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (suite.resumedFromRunId != null) {
            Text("${suite.reusedStageIndices.size} stages retained from an earlier campaign. New measurements have a separate observation window.", style = MaterialTheme.typography.bodySmall)
        }
        val counts = suite.results.groupingBy { it.status }.eachCount()
        Text(counts.entries.joinToString(" · ") { "${it.value} ${it.key.display()}" }, style = MaterialTheme.typography.bodySmall)
        if (!state.running) {
            VoiceDiagnosticExportControl(
                content = BenchmarkExportContent.VOICE_SUITE,
                label = "Export full voice benchmark",
                tag = "voice_benchmark_suite_export"
            ) { output -> viewModel.exportSuite(suite, output) }
            if (suite.resumedFromRunId != null) {
                VoiceDiagnosticExportControl(
                    content = BenchmarkExportContent.VOICE_SUITE,
                    label = "Export preceding interrupted benchmark",
                    tag = "voice_benchmark_previous_suite_export"
                ) { output -> viewModel.exportPreviousSuite(suite, output) }
            }
            var expanded by rememberSaveable(suite.runId) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide stage results" else "Show stage results") }
            if (expanded) {
                val locale = LocalLocale.current.platformLocale
                suite.results.forEach { row ->
                    Text("${row.label} · ${row.status.display()}" + (row.reason?.let { " · ${it.lowercase(Locale.ROOT).replace('_', ' ')}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    row.recommendedRoute?.let { Text("Qualified choice for this test · $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    row.measuredLatencyCandidate?.let { Text("Lowest measured streaming delay · $it. Acoustic quality requires independent measurements.", style = MaterialTheme.typography.bodySmall) }
                    row.metrics.forEach { metric ->
                        val value = String.format(locale, "%.1f", metric.value)
                        Text("${metric.label} · $value ${metric.unit} · n=${metric.samples}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (state.history.isNotEmpty() && !state.running) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide earlier tests" else "Earlier individual tests") }
        if (expanded) {
            val selected = state.history.indexOfFirst { it.startedAtMillis == state.report?.startedAtMillis }.coerceAtLeast(0)
            VoiceChoice(
                "Earlier test",
                selected.toString(),
                state.history.mapIndexed { i, report ->
                    i.toString() to "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.startedAtMillis))} · ${report.configuration.engine.label}"
                },
                { it.toIntOrNull()?.let(viewModel::selectHistory) }
            )
            val report = state.history[selected]
            VoiceSpeechBenchmarkResults(report)
            VoiceSpeechBenchmarkExportControl(report)
        }
    }
    if (state.nativeCleanupPending) Text("Waiting for the voice runtime to release the audio device.", style = MaterialTheme.typography.bodySmall)
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
}

private fun VoiceBenchStatus.display(): String = when (this) {
    VoiceBenchStatus.PENDING -> "not run"
    else -> name.lowercase(Locale.ROOT)
}

@Composable
private fun VoiceSpeechBenchmarkResults(report: VoiceSpeechBenchmarkReport) {
    val nativeEngine = report.configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
    val outcome = when (report.outcome) {
        VoiceSpeechBenchmarkOutcome.RUNNING -> "Running"
        VoiceSpeechBenchmarkOutcome.QUALIFIED -> if (report.configuration.playback) "Finished · controlled playback suite qualified" else "Finished · workload qualified"
        VoiceSpeechBenchmarkOutcome.PARTIAL -> "Finished · partial results"
        VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON -> if (report.configuration.playback) "Finished · measured samples, no qualified ranking" else "Finished · no qualified comparison"
        VoiceSpeechBenchmarkOutcome.INVALID_WORKLOAD -> "Stopped · invalid test input"
        VoiceSpeechBenchmarkOutcome.CANCELLED -> "Cancelled · completed measurements retained"
        VoiceSpeechBenchmarkOutcome.INTERRUPTED -> "Interrupted · completed measurements retained"
        VoiceSpeechBenchmarkOutcome.HISTORICAL -> "Historical workload · comparison unavailable"
    }
    Text(outcome, style = MaterialTheme.typography.titleSmall)
    if (report.outcome == VoiceSpeechBenchmarkOutcome.PARTIAL) {
        report.results.filter { it.failure != null }.forEach { result ->
            val reason = result.failureObservation?.validationReason ?: result.failure?.name
            Text("${result.requestedBackend.label} · ${reason.orEmpty().lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (nativeEngine) {
        Text("${report.configuration.nativeModelVariant.displayName} · HTP policy: ${report.configuration.htpPerformanceMode.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("SDK input: ${if (report.configuration.incrementalInput) "incremental reply stream" else "separate phrase streams"}${if (report.configuration.engine.mediaPipeEngine()?.isKokoro == true) " · ${report.configuration.kokoroBucket.label}" else ""}", style = MaterialTheme.typography.bodySmall)
        report.measuredStreamingCandidate()?.let { Text("Lowest measured streaming delay · ${it.label}", style = MaterialTheme.typography.bodyMedium) }
        report.streamingComparisons().forEach { comparison ->
            val latency = comparison.medianFirstPresentedMs?.let { "${format(it)} ms median warm start" } ?: "Warm start not measured"
            val coverage = comparison.captionCoverage?.let { "${format(it * 100)}% caption boundary coverage" } ?: "Caption coverage not measured"
            Text("${comparison.backend.label} · $latency · $coverage", style = MaterialTheme.typography.bodySmall)
            Text("Independent speech quality: ${comparison.independentQualityStatus.lowercase(Locale.ROOT).replace('_', ' ')}. Caption coverage does not measure acoustic word accuracy.", style = MaterialTheme.typography.bodySmall)
            if (comparison.exclusions.isNotEmpty()) Text("Comparison pending: ${comparison.exclusions.joinToString { it.lowercase(Locale.ROOT).replace('_', ' ') }}", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        "${report.configuration.engine.label} · ${if (nativeEngine) "${report.configuration.voice.label} · ${report.configuration.threads} threads" else "voice ${report.configuration.engineVoiceId ?: "unknown"} · SDK threads unobserved"} · ${format(report.configuration.speechRate.toDouble())}× · build ${report.configuration.appBuild}",
        style = MaterialTheme.typography.bodySmall
    )
    val notice = when (report.issue) {
        VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE -> "Install or repair ${report.configuration.engine.label} in Voice settings before testing."
        VoiceSpeechBenchmarkIssue.THERMAL_LIMIT -> "Stopped because the phone became warm. Let it cool before comparing engines. Completed measurements are retained."
        VoiceSpeechBenchmarkIssue.EXECUTION_FAILED -> "The benchmark could not finish. Completed measurements are retained."
        VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD -> "A test input violated the speech engine contract. This does not establish a processor failure. Export the retained diagnostic result."
        VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING -> "Audio stopped, but native cleanup was still pending when this result was captured. No further processor test started."
        VoiceSpeechBenchmarkIssue.CANCELLED -> "Stopped at your request. Unfinished backends have no performance result."
        null -> null
    }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    report.recommendation()?.let { Text("Recommendation for this test: ${it.label}. An accelerator needs at least a ten percent gain across the same qualified workload, including startup cost.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
    if (!nativeEngine) {
        val scope = if (report.configuration.pcmProcessingId == VoiceTempoProcessor.IMPLEMENTATION_ID) {
            "PCM timing and signal are measured after app tempo processing and before playback pause trimming."
        } else {
            "PCM processing scope is unqualified for this saved report."
        }
        Text(scope, style = MaterialTheme.typography.bodySmall)
    }
    val order = report.configuration.plannedBackendOrder.joinToString(" → ") { it.label }
    val schedule = if (report.configuration.comparesColdStartsFirst) "Each processor runs one fresh trial in this order, then the remaining trials follow the same order." else "Repeat runs rotate the starting processor."
    Text("$order. $schedule", style = MaterialTheme.typography.bodySmall)
    if (report.configuration.playback) {
        val completed = report.playbackResults.count { it.state == VoiceSpeechPlaybackState.COMPLETE }
        Text("${report.playbackResults.size} trials recorded · $completed played to completion · up to ${report.totalSamples} planned · ${report.configuration.calibrationPolicy.label} · configured offset ${report.configuration.captionOffsetMs} ms", style = MaterialTheme.typography.bodySmall)
        if (VoiceSpeechPlaybackScenario.SUSTAINED in report.configuration.playbackScenarios) Text("Sustained target: ${report.configuration.sustainedTargetAudioMs / 60_000} minutes of completed, validated and presented audio per processor.", style = MaterialTheme.typography.bodySmall)
        for (backend in report.configuration.plannedBackendOrder) {
            val state = if (nativeEngine) report.results.find { it.requestedBackend == backend }?.state else report.engineResults.find { it.requestedBackend == backend }?.state
            val evidence = if (nativeEngine) "audio consistency ${report.playbackAudioReference(backend).name.lowercase().replace('_', ' ')}" else "SDK processor placement unobserved"
            Text("${backend.label} · ${state?.name?.lowercase()?.replace('_', ' ') ?: "unobserved"} · $evidence", style = MaterialTheme.typography.bodySmall)
            val rows = report.playbackResults.filter { it.requestedBackend == backend }
            var expanded by remember(report.startedAtMillis, backend) { mutableStateOf(false) }
            if (rows.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${backend.label} · ${rows.size} observed samples", style = MaterialTheme.typography.titleSmall)
                        rows.firstOrNull { it.scenario == VoiceSpeechPlaybackScenario.COLD_START && it.repeat == 0 }?.firstPresentedAudioMs?.let {
                            Text("First fresh-session audio presented at ${format(it / 1_000.0)} s", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${rows.count { it.failure != null }} failures · ${rows.sumOf { it.bufferRestarts }} restarts · ${rows.count { it.cleanupState == "PENDING" }} pending cleanup", style = MaterialTheme.typography.bodySmall)
                        Text(rows.groupingBy { it.quality }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key.name.lowercase().replace('_', ' ')}" }, style = MaterialTheme.typography.bodySmall)
                        if (report.configuration.mode == VoiceSpeechBenchmarkMode.STABILITY) {
                            VoiceSpeechBenchmarkFixture.entries.forEach { fixture ->
                                val observations = report.warmPresentationTimes(backend, fixture)
                                if (observations.size >= 20) {
                                    val p50 = VoiceSpeechBenchmarkBackendResult.percentile(observations, .50)
                                    val p95 = VoiceSpeechBenchmarkBackendResult.percentile(observations, .95)
                                    Text("${fixture.label} warm first presented audio · p50 $p50 ms · p95 $p95 ms · n=${observations.size}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val gaps = rows.filter { !it.warmup }.flatMap { row -> row.chunkRecords.mapNotNull { it.join?.readyUnpunctuatedGapMs } }
                            if (gaps.size >= 20) {
                                Text("Text-ready unpunctuated joins · p95 ${VoiceSpeechBenchmarkBackendResult.percentile(gaps, .95)} ms · n=${gaps.size} · retained PCM silence plus observed track refill", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide samples" else "Inspect samples and causes") }
                    }
                }
                if (expanded) rows.forEach { VoiceSpeechPlaybackResultCard(it, report.configuration) }
            }
        }
    } else if (nativeEngine) {
        Text("${report.configuration.workload.label} · ${report.completedSamples}/${report.totalSamples} fixture iterations · ${report.completedChunks} native chunks", style = MaterialTheme.typography.bodySmall)
        val qualified = report.qualifiedResults()
        for (result in report.results) {
            VoiceSpeechBenchmarkResultCard(result, report.audioReference(result), report.rejectedOutput(result), result in qualified)
        }
    } else {
        Text("${report.configuration.workload.label} · ${report.completedSamples}/${report.totalSamples} fixture iterations", style = MaterialTheme.typography.bodySmall)
        for (result in report.engineResults) VoiceSpeechEngineBenchmarkCard(result)
    }
    for (observation in report.energyObservations) VoiceSpeechBenchmarkEnergySummary(observation, report.configuration.comparesColdStartsFirst)
    Text(
        "Engineering goals · not measured results: preparation to first PCM below ${VoiceSpeechBenchmarkTargets.PREPARE_TO_FIRST_PCM_MS} ms; warm first PCM below ${VoiceSpeechBenchmarkTargets.WARM_FIRST_PCM_MS} ms; long-speech RTF below ${VoiceSpeechBenchmarkTargets.LONG_SPEECH_RTF}; zero clipped samples or playback interruptions. Accelerator kernel time share above ${(VoiceSpeechBenchmarkTargets.ACCELERATOR_KERNEL_TIME_SHARE * 100).toInt()}% is a goal where feasible and does not determine qualification.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        if (report.configuration.playback) {
            "Presented audio uses the production AudioTrack timestamp or playback head. Caption checks validate that clock, not perceptual word accuracy. Independent fixture manifests detect missing caption sections. Acoustic speaker latency, acoustic word alignment, CPU/GPU/NPU utilization and energy attributable to an engine remain unmeasured."
        } else if (nativeEngine) {
            "First PCM is the first complete native chunk; total synthesis includes every chunk. Accelerator ranking requires comparable duration, token timing and signal against CPU for the same input and settings. This numerical check does not establish listening quality. Playback and acoustic latency, underruns and energy attributable to an engine are unmeasured. First use retains the phone's file cache. Medians average the middle pair; p95 is withheld below twenty samples."
        } else {
            "First PCM is the first nonempty engine output callback. Timings include the engine's stream calls and callback delivery. The spoken fixtures are shared, but each engine has its own voice and internal timing. Intelligibility, listening quality, acoustic latency, energy attributable to an engine and actual processor placement remain unmeasured. These results do not select a cross-engine winner."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VoiceSpeechBenchmarkEnergySummary(observation: VoiceSpeechBenchmarkEnergyObservation, splitColdTrial: Boolean) {
    val summary = when (observation.status) {
        VoiceSpeechEnergyStatus.IN_PROGRESS -> "observing whole-device battery counter"
        VoiceSpeechEnergyStatus.INTERRUPTED -> "battery observation interrupted; final endpoint unavailable"
        VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE -> "battery energy counter unavailable"
        VoiceSpeechEnergyStatus.POWER_STATE_UNKNOWN -> "power state unknown; no counter difference reported"
        VoiceSpeechEnergyStatus.EXTERNAL_POWER_OBSERVED -> "external power observed; no counter difference reported"
        VoiceSpeechEnergyStatus.MONITOR_UNCONFIRMED -> "power monitoring unconfirmed; endpoints only"
        VoiceSpeechEnergyStatus.TIMING_UNAVAILABLE -> "observation interval unavailable; endpoints only"
        VoiceSpeechEnergyStatus.COUNTER_ZERO_OR_RESET -> "zero counter or possible reset; endpoints only"
        VoiceSpeechEnergyStatus.COUNTER_INCREASED -> "counter increased; possible recharge or reset"
        VoiceSpeechEnergyStatus.NO_COUNTER_CHANGE -> "counter unchanged; this does not establish zero consumption"
        VoiceSpeechEnergyStatus.COUNTER_DECREASE_OBSERVED -> "whole-device counter decreased by ${format(checkNotNull(observation.observedCounterDecreaseNanowattHours) / 1_000_000.0)} mWh over ${format(checkNotNull(observation.elapsedMs) / 1000.0)} s"
    }
    val phase = if (!splitColdTrial) {
        ""
    } else if (observation.window == 1) {
        " · first fresh trial"
    } else {
        " · remaining trials"
    }
    Text("${observation.requestedBackend.label}$phase · $summary", style = MaterialTheme.typography.bodySmall)
    if (observation.status == VoiceSpeechEnergyStatus.COUNTER_DECREASE_OBSERVED) {
        Text("Includes the display, background apps, preparation and reporting. Device counter resolution and power-event delivery vary; this observation cannot establish engine energy, power or savings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VoiceSpeechBenchmarkResultCard(
    result: VoiceSpeechBenchmarkBackendResult,
    reference: VoiceSpeechBenchmarkAudioReference,
    rejectedOutput: VoiceSpeechRejectedOutput?,
    qualified: Boolean
) {
    var expanded by remember(result.requestedBackend) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.requestedBackend.label, style = MaterialTheme.typography.titleSmall)
            Text(result.state.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            result.failure?.let { failure ->
                Text("${failure.name.lowercase().replace('_', ' ')}${result.failureStage?.let { " · ${it.name.lowercase()}" }.orEmpty()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                result.failedFixture?.let { fixture ->
                    Text("Failed: ${fixture.label} · repeat ${result.failedRepeat ?: "—"} · chunk ${result.failedChunk?.plus(1) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                }
                result.failedInputBytes?.let { Text("Input: $it bytes · limit ${result.failedInputByteLimit ?: "—"} bytes", style = MaterialTheme.typography.bodySmall) }
                result.failedTokenCount?.let { Text("Tokens: $it · limit ${result.failedTokenLimit ?: "—"}", style = MaterialTheme.typography.bodySmall) }
                result.failedAttemptMs?.let { Text("Failed attempt: $it ms · requested ${result.failedRequestedProvider ?: result.requestedBackend.label}", style = MaterialTheme.typography.bodySmall) }
                result.failedDiscoverySummary?.let { Text("Device discovery: $it", style = MaterialTheme.typography.bodySmall) }
                result.failureEffectiveProvider?.let { Text("Observed provider: $it. This is not a successful ${result.requestedBackend.label} measurement.", style = MaterialTheme.typography.bodySmall) }
            }
            Text(if (result.executionVerified) "Requested processor execution observed" else "Requested processor execution not established", style = MaterialTheme.typography.bodySmall)
            val comparison = reference.comparison
            Text(
                when (comparison.status) {
                    VoiceSpeechAudioReferenceStatus.CPU_REFERENCE -> "CPU audio reference recorded"
                    VoiceSpeechAudioReferenceStatus.CONSISTENT -> "Audio passed duration, token timing and signal consistency checks against CPU"
                    VoiceSpeechAudioReferenceStatus.MISMATCH -> "Audio differs from CPU: ${comparison.reason?.name?.lowercase()?.replace('_', ' ')}"
                    VoiceSpeechAudioReferenceStatus.UNOBSERVED -> "CPU audio comparison unavailable: ${comparison.reason?.name?.lowercase()?.replace('_', ' ')}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (comparison.status == VoiceSpeechAudioReferenceStatus.MISMATCH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!qualified) Text("Diagnostic timings only · this result is ineligible for ranking", style = MaterialTheme.typography.bodySmall)
            comparison.durationRatio?.let { Text("${reference.fixture?.label} · repeat ${reference.repeat} · chunk ${reference.chunk?.plus(1)}: audio ${comparison.candidateAudioMs?.let(::format)} ms / CPU ${comparison.referenceAudioMs?.let(::format)} ms", style = MaterialTheme.typography.bodySmall) }
            result.cancellationStage?.let { Text("Stop requested during ${it.name.lowercase()}", style = MaterialTheme.typography.bodySmall) }
            result.cancellationNativeRetirementMs?.let { Text("Native cleanup finished $it ms after the stop request. Graph preparation may need to finish before it can retire.", style = MaterialTheme.typography.bodySmall) }
            result.stopReason?.let { Text("Stop reason: ${it.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            result.deadline?.let { Text("Deadline: ${it.scope.name.lowercase()} · ${it.elapsedMs}/${it.limitMs} ms${it.stage?.let { stage -> " · ${stage.name.lowercase()}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            result.cleanupState?.let { Text("Native cleanup: ${it.name.lowercase()}${result.deadlineNativeRetirementMs?.let { time -> " · $time ms after deadline request" }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            result.failureObservation?.let { observation ->
                observation.validationReason?.let { Text("Validation: ${it.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
                if (observation.audioMeasurementAvailable != true) Text("PCM statistics were not measured or are unknown for this failure.", style = MaterialTheme.typography.bodySmall)
                VoiceContextPreparationDetails(observation.graphPreparationSource, observation.contextCacheStatus, observation.contextLoadMs, observation.contextStoreMs, observation.contextBytes, observation.contextFallback)
            }
            rejectedOutput?.let {
                Text(
                    "Rejected output ${it.candidateFrames} frames / CPU ${it.cpuFrames} frames · duration ratio ${format(it.durationRatio)}" +
                        it.tokenTimingDistance?.let { distance -> " · normalized token timing difference ${format(distance)}" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            result.firstInferenceProfiles()?.let { profiles ->
                profiles.acceleratorShare?.let { share ->
                    Text("${profiles.count} distinct first-inference profiles · ${format(share * 100)}% accelerator kernel time", style = MaterialTheme.typography.bodySmall)
                }
            }
            result.sessionPrepareMs?.let { Text("Fresh session: $it ms", style = MaterialTheme.typography.bodySmall) }
            result.samples.firstOrNull()?.prepareToFirstPcmMs?.let { Text("Prepare to first PCM: $it ms", style = MaterialTheme.typography.bodySmall) }
            if (result.incompleteChunks.isNotEmpty()) Text("${result.incompleteChunks.size} successful chunks retained from the unfinished ${result.incompleteFixture?.label?.lowercase() ?: "current"} iteration.", style = MaterialTheme.typography.bodySmall)
            if (result.samples.isNotEmpty()) {
                for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                    val statistics = result.fixtureStatistics(fixture)
                    if (statistics.sampleCount > 0) {
                        Text("${fixture.label} · ${statistics.warmSampleCount} warm samples", style = MaterialTheme.typography.labelLarge)
                        Text("Median ${statistics.medianWarmMs?.let(::format) ?: "—"} ms · p95 ${statistics.p95WarmMs?.let { "$it ms" } ?: "needs 20 samples"} · RTF ${statistics.medianWarmRtf?.let(::format) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        Text("First-chunk median ${statistics.medianFirstPcmMs?.let(::format) ?: "—"} ms · ${statistics.completedChunks} chunks", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Measured workload cost: ${result.measuredCostMs} ms including session preparation and retained partial work. RTF below 1.00 generates faster than audio duration.", style = MaterialTheme.typography.bodySmall)
                val latest = result.samples.last()
                val chunk = latest.chunks.lastOrNull()
                Text("Observed: ${latest.effectiveProvider}${if (latest.acceleratorKernels > 0 && latest.cpuKernels > 0) " + CPU" else ""}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (chunk?.profileAvailable == true) "Latest shape profile: ${chunk.acceleratorKernels} accelerator / ${chunk.cpuKernels} CPU kernel events${if (chunk.profileReused == true) " · reused observation" else ""}" else "Kernel profile not collected for this provider",
                    style = MaterialTheme.typography.bodySmall
                )
                if (chunk?.profileAvailable == true) {
                    Text("Profiled kernel time: ${format(chunk.acceleratorMicros / 1000.0)} ms accelerator · ${format(chunk.cpuMicros / 1000.0)} ms CPU", style = MaterialTheme.typography.bodySmall)
                }
                chunk?.cacheHit?.let { Text("Latest graph: ${if (it) "reused" else "prepared"} · current preparation ${chunk.currentPrepareMs ?: "—"} ms · ${chunk.cachedShapeCount ?: "—"}/${chunk.cacheLimit ?: "—"} shapes retained", style = MaterialTheme.typography.bodySmall) }
                chunk?.let { VoiceContextPreparationDetails(it.graphPreparationSource, it.contextCacheStatus, it.contextLoadMs, it.contextStoreMs, it.contextBytes, it.contextFallback) }
                chunk?.cacheBudgetBytes?.let { Text("Graph retention: ${chunk.cacheEstimatedBytes?.div(1024 * 1024) ?: "—"} / ${it / (1024 * 1024)} MiB estimated · ${chunk.cacheAdmission?.lowercase()?.replace('_', ' ')}. Available memory and the byte ceiling determine how many shapes fit.", style = MaterialTheme.typography.bodySmall) }
                chunk?.audioRms?.let { Text("PCM RMS ${format(it)} · peak ${chunk.audioPeak?.let(::format) ?: "—"} · clipped samples ${chunk.clippedSamples ?: "—"}. Structural checks do not establish listening quality.", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide samples" else "View all samples") }
                if (expanded) {
                    for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                        val rows = result.samples.filter { it.fixture == fixture }
                        if (rows.isNotEmpty()) {
                            Text(fixture.label, style = MaterialTheme.typography.labelLarge)
                            for (sample in rows) {
                                Text(
                                    "${if (sample.warm) "Warm ${sample.repeat}" else "First iteration"}: ${sample.generationMs} ms total · first PCM ${sample.firstPcmMs} ms · ${format(sample.audioMs / 1000)} s audio · RTF ${sample.realTimeFactor?.let(::format) ?: "—"} · thermal ${sample.thermalBefore ?: "—"} → ${sample.thermalAfter ?: "—"}",
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
private fun VoiceSpeechPlaybackResultCard(result: VoiceSpeechPlaybackResult, configuration: VoiceSpeechBenchmarkConfiguration) {
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${result.requestedBackend.label} · ${result.scenario.label} · sample ${result.repeat + 1}${if (result.warmup) " · warm-up" else ""}", style = MaterialTheme.typography.titleSmall)
            Text("${result.state.name.lowercase()} · ${result.quality.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
            result.failure?.let { Text("${it.name.lowercase().replace('_', ' ')}${result.failureStage?.let { stage -> " · ${stage.name.lowercase()}" }.orEmpty()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            result.renderedManifestWords?.let { rendered ->
                Text("Caption words on playback clock ${result.manifestWordsObserved}/${result.manifestWordsExpected} · render callbacks $rendered", style = MaterialTheme.typography.bodySmall)
            }
            result.cancellationMs?.let { Text("Playback stopped after $it ms", style = MaterialTheme.typography.bodySmall) }
            result.nativeRetirementMs?.let { Text("Native workers retired after $it ms from stop request", style = MaterialTheme.typography.bodySmall) }
            result.pipelineFailureCode?.let { Text("Playback cause: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result.nativeCode?.let { Text("Native code: $it · requested ${result.failedRequestedProvider ?: "unknown"} · observed ${result.failedEffectiveProvider ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
            result.failedChunk?.let { Text("Failed chunk ${it + 1} · input ${result.failedInputBytes ?: "unknown"}/${result.failedInputByteLimit ?: "unknown"} bytes · tokens ${result.failedTokenCount ?: "unknown"}/${result.failedTokenLimit ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
            result.timeoutLimitMs?.let { Text("Deadline: ${result.timeoutScope ?: "unknown"} · ${result.timeoutElapsedMs ?: "unknown"}/$it ms", style = MaterialTheme.typography.bodySmall) }
            result.sustainedTargetAudioMs?.let { target ->
                Text("Sustained audio: ${result.sustainedObservedAudioMs ?: 0}/$target ms · ${result.sustainedCompletedRepeats ?: 0} completed replies · ${result.sustainedQualification?.lowercase()?.replace('_', ' ') ?: "qualification unobserved"}", style = MaterialTheme.typography.bodySmall)
                Text("Series elapsed: ${result.sustainedWallElapsedMs ?: "unmeasured"}/${result.sustainedWallDeadlineMs ?: "unknown"} ms wall limit", style = MaterialTheme.typography.bodySmall)
            }
            Text("First PCM ${result.firstPcmMs ?: "—"} ms · queued ${result.firstQueuedPcmMs ?: "—"} ms · presented ${result.firstPresentedAudioMs ?: "—"} ms", style = MaterialTheme.typography.bodySmall)
            result.prewarmMode?.let { mode ->
                val outcome = if (result.prewarmCompleted == true) "completed" else "incomplete"
                Text("Prewarm ${mode.name.lowercase(Locale.ROOT).replace('_', ' ')} · $outcome · ${result.prewarmElapsedMs ?: "—"} ms", style = MaterialTheme.typography.bodySmall)
            }
            result.firstSdkPcmAfterStreamStartMs?.let { milliseconds ->
                Text("Stream call to first engine PCM · $milliseconds ms", style = MaterialTheme.typography.bodySmall)
            }
            result.sdkPcm?.let { pcm ->
                val scope = if (configuration.pcmProcessingId == VoiceTempoProcessor.IMPLEMENTATION_ID) "After app tempo, before pause trimming" else "Engine output · processing version unqualified"
                Text("$scope · peak ${format(pcm.peak)} · RMS ${format(pcm.rms)} · ${pcm.clipped} clipped samples", style = MaterialTheme.typography.bodySmall)
            }
            result.stopOrigin?.let { origin ->
                Text("Stop requested by ${origin.name.lowercase(Locale.ROOT)} · audio stopped in ${result.cancellationMs ?: "—"} ms · engine retired in ${result.nativeRetirementMs ?: "—"} ms", style = MaterialTheme.typography.bodySmall)
            }
            Text("AudioTrack write ${result.firstAudioTrackWriteMs ?: "unmeasured"} ms · play requested ${result.trackPlayRequestedMs ?: "unmeasured"} ms · model preparation ${result.sessionPrepareMs ?: "unmeasured"} ms", style = MaterialTheme.typography.bodySmall)
            Text(if (result.requestedDelegate == null) "Requested/effective threads ${result.requestedThreads ?: "unknown"}/${result.effectiveThreads ?: "unknown"} · engine reused ${result.engineReused ?: "unknown"}" else "${result.requestedDelegate} delegate requested · actual processor and threads unobserved · engine reused ${result.engineReused ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
            Text("${if (result.requestedDelegate == null) "${result.validatedChunks}/${result.chunks} native chunks verified · completed generation" else "${result.chunks} delivered sections · PCM production intervals"} ${result.totalSynthesisMs} ms · audio ${result.totalAudioMs} ms · elapsed ${result.elapsedMs} ms", style = MaterialTheme.typography.bodySmall)
            if (result.requestedDelegate != null) Text("${result.engineStreamCalls ?: "unknown"} engine stream calls · structural audio ${if (result.streamedAudioValidated == true) "validated" else "not established"}", style = MaterialTheme.typography.bodySmall)
            if (result.requestedDelegate != null) Text("PCM production intervals exclude callback backpressure and work after the last retained callback; they are not complete stream-call wall time.", style = MaterialTheme.typography.bodySmall)
            result.engineStreamWallMs?.let { Text("Complete engine stream calls: $it ms, including processing and consumer callback waits.", style = MaterialTheme.typography.bodySmall) }
            if (result.omittedPcmRecords > 0) Text("${result.omittedPcmRecords} later PCM receipts omitted from the bounded trace; their timing and audio remain in the totals.", style = MaterialTheme.typography.bodySmall)
            if (result.requestedDelegate == null) Text("Completed generation includes graph preparation within each chunk. Session loading and failed attempts are recorded separately.", style = MaterialTheme.typography.bodySmall)
            Text("Underruns ${result.underruns ?: "unmeasured"} · refills ${result.bufferRestarts} · longest refill ${result.longestRefillMs} ms", style = MaterialTheme.typography.bodySmall)
            Text("Caption observations ${result.captionObservations} · clock regressions ${result.captionClockRegressions} · timing violations ${result.captionTimingViolations}", style = MaterialTheme.typography.bodySmall)
            Text("Fixture words: ${result.manifestWordsObserved ?: "unmeasured"}/${result.manifestWordsExpected ?: "unknown"} observed · ${result.manifestWordsPrepared ?: "unmeasured"} prepared · ${result.unexpectedCaptionWords ?: "unknown"} unexpected", style = MaterialTheme.typography.bodySmall)
            Text("Active underruns ${result.activePlaybackUnderruns ?: "unmeasured"} · startup ${result.startupUnderruns ?: "unmeasured"} · drain ${result.drainUnderruns ?: "unmeasured"}", style = MaterialTheme.typography.bodySmall)
            Text("Applied caption offsets: ${result.calibrations.joinToString { "${it.route} ${it.offsetMs} ms" }.ifBlank { "unmeasured" }}", style = MaterialTheme.typography.bodySmall)
            Text("${result.effectiveProvider ?: "execution unverified"} · ${result.route ?: "route unobserved"} · ${result.clockBasis ?: "clock unobserved"}", style = MaterialTheme.typography.bodySmall)
            Text("Thermal ${result.thermalBefore ?: "unknown"} → ${result.thermalAfter ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
            result.cancellationTarget?.let { Text("Cancellation target ${it.name.lowercase()} · reached ${result.cancellationTargetReached ?: false} · cleanup ${result.cleanupState.lowercase()}", style = MaterialTheme.typography.bodySmall) }
            result.cancellationMs?.let { Text("Audio stop after cancellation: $it ms", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun VoiceSpeechBenchmarkExportControl(report: VoiceSpeechBenchmarkReport) {
    VoiceDiagnosticExportControl(
        content = dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportContent.VOICE_SPEECH,
        label = "Export benchmark results",
        tag = "voice_speech_benchmark_export"
    ) { output -> dev.chungjungsoo.gptmobile.data.voice.writeVoiceSpeechBenchmarkExport(report, output) }
}

@Composable
private fun VoiceContextPreparationDetails(source: String?, status: String?, loadMs: Long?, storeMs: Long?, bytes: Long?, fallback: Boolean?) {
    source?.let { Text("Graph source: ${it.lowercase(Locale.ROOT).replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
    status?.let {
        Text(
            "Saved context: ${it.lowercase(Locale.ROOT).replace('_', ' ')}" +
                (loadMs?.let { time -> " · load $time ms" } ?: "") +
                (storeMs?.let { time -> " · save $time ms" } ?: "") +
                (bytes?.let { count -> " · ${format(count / (1024.0 * 1024))} MiB" } ?: "") +
                (if (fallback == true) " · source model retry" else ""),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)
