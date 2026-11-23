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
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkWorkload
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechCalibrationPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackScenario
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
    captionOffsetMs: Int = 0,
    viewModel: VoiceSpeechBenchmarkViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(VoiceSpeechBenchmarkMode.QUICK.name) }
    var workload by rememberSaveable { mutableStateOf(VoiceSpeechBenchmarkWorkload.UTTERANCE.name) }
    var audible by rememberSaveable { mutableStateOf(false) }
    var playbackScenario by rememberSaveable { mutableStateOf(VoiceSpeechPlaybackScenario.COLD_START.name) }
    var calibrationPolicy by rememberSaveable { mutableStateOf(VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO.name) }
    var selected by rememberSaveable { mutableStateOf(VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS.map { it.name }) }
    VoiceSettingsSection("Speech benchmark")
    Text("Compare CPU, GPU and NPU on this phone. Fixed samples and numeric results are saved for up to five runs.", style = MaterialTheme.typography.bodySmall)
    VoiceChoice("Measurement", if (audible) "playback" else "silent", listOf("silent" to "Silent synthesis", "playback" to "Audible playback and captions"), { audible = it == "playback" }, enabled = enabled && !state.running)
    if (audible) {
        VoiceChoice("Playback workload", playbackScenario, VoiceSpeechPlaybackScenario.entries.map { it.name to it.label } + ("ALL" to "All six workloads"), { playbackScenario = it }, enabled = enabled && !state.running)
        VoiceChoice("Test length", mode, VoiceSpeechBenchmarkMode.entries.map { it.name to it.label }, { mode = it }, enabled = enabled && !state.running)
        VoiceChoice("Caption calibration", calibrationPolicy, VoiceSpeechCalibrationPolicy.entries.map { it.name to it.label }, { calibrationPolicy = it }, enabled = enabled && !state.running)
        Text("Fresh, resident, streamed, changing-shape, sustained and cancellation tests use the real speech queue. Warm tests keep the same engine. All six full workloads with controlled calibration are required for a ranking; quick samples remain diagnostics.", style = MaterialTheme.typography.bodySmall)
    } else {
        VoiceChoice("Workload", workload, VoiceSpeechBenchmarkWorkload.entries.map { it.name to it.label }, { workload = it }, enabled = enabled && !state.running)
        VoiceChoice("Test length", mode, VoiceSpeechBenchmarkMode.entries.map { it.name to "${it.label} · ${it.warmRepeats} warm runs per sample" }, { mode = it }, enabled = enabled && !state.running)
        Text("Production phrases preserve the whole response. Native phrase tests isolate a single call. Changing shapes measures graph reuse when phrase lengths alternate.", style = MaterialTheme.typography.bodySmall)
    }
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
                viewModel.start(
                    VoiceSpeechBenchmarkMode.valueOf(mode),
                    VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS.filter { it.name in selected },
                    voice,
                    threads,
                    speechRate,
                    workload = if (audible) VoiceSpeechBenchmarkWorkload.UTTERANCE else VoiceSpeechBenchmarkWorkload.valueOf(workload),
                    playback = audible,
                    playbackScenarios = if (playbackScenario == "ALL") VoiceSpeechPlaybackScenario.entries else listOf(VoiceSpeechPlaybackScenario.valueOf(playbackScenario)),
                    calibrationPolicy = VoiceSpeechCalibrationPolicy.valueOf(calibrationPolicy),
                    captionOffsetMs = captionOffsetMs
                )
            }
        },
        enabled = if (state.running) !state.cancelling else enabled && state.loaded && !state.nativeCleanupPending && voice != null && selected.isNotEmpty(),
        modifier = Modifier.testTag("voice_speech_benchmark_run")
    ) {
        Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
        Text(
            if (state.cancelling) {
                "Stopping benchmark…"
            } else if (state.running) {
                "Stop benchmark"
            } else {
                if (audible) "Run audible playback test" else "Run ${mode.lowercase()} benchmark"
            },
            Modifier.padding(start = 8.dp)
        )
    }
    if (state.running) {
        val report = state.report
        LinearProgressIndicator(
            progress = {
                report?.let {
                    it.completedSamples.toFloat() / it.totalSamples.coerceAtLeast(1)
                } ?: 0f
            },
            modifier = Modifier.fillMaxWidth()
        )
        val active = report?.results?.firstOrNull { it.state in setOf(VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING) }
        Text(
            listOfNotNull(active?.requestedBackend?.label, report?.activeFixture?.label, report?.activeRepeat?.let { if (it == 0) "first use" else "warm run $it" }, report?.activeChunk?.let { "chunk ${it + 1}" }).joinToString(" · ").ifBlank { "Checking installed voice model…" },
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
                    recorded.runtimeId != TimedKokoro.RUNTIME_ID ||
                    !recorded.currentProtocol ||
                    recorded.sourceCommit != BuildConfig.APP_COMMIT
                )
        ) {
            Text("These saved results used different settings or an earlier app/runtime. Run again to compare your current setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        VoiceSpeechBenchmarkResults(report)
        if (!state.running) VoiceSpeechBenchmarkExportControl(report)
    }
    if (state.nativeCleanupPending) Text("Audio stopped · waiting for native cleanup. Other speech work remains paused.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    if (!state.saved) Text("Results are visible, but could not be saved on this phone. Export them before leaving.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun VoiceSpeechBenchmarkResults(report: VoiceSpeechBenchmarkReport) {
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
    Text(
        "${report.configuration.voice.label} · ${report.configuration.threads} threads · ${format(report.configuration.speechRate.toDouble())}× · build ${report.configuration.appBuild}",
        style = MaterialTheme.typography.bodySmall
    )
    val notice = when (report.issue) {
        VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE -> "Install or repair the Kokoro model in Voice settings before testing."
        VoiceSpeechBenchmarkIssue.THERMAL_LIMIT -> "Stopped because the phone became warm. Let it cool before comparing engines. Completed measurements are retained."
        VoiceSpeechBenchmarkIssue.EXECUTION_FAILED -> "The benchmark could not finish. Completed measurements are retained."
        VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD -> "A test input violated the speech engine contract. This does not establish a processor failure. Export the retained diagnostic result."
        VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING -> "Audio stopped, but native cleanup was still pending when this result was captured. No further processor test started."
        VoiceSpeechBenchmarkIssue.CANCELLED -> "Stopped at your request. Unfinished backends have no performance result."
        null -> null
    }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    report.recommendation()?.let { Text("Recommendation for this test: ${it.label}. An accelerator needs at least a ten percent gain across the same qualified workload, including startup cost.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
    Text("Run order: ${report.configuration.plannedBackendOrder.joinToString(" → ") { it.label }}. Repeat runs rotate the starting processor.", style = MaterialTheme.typography.bodySmall)
    if (report.configuration.playback) {
        Text("${report.completedSamples}/${report.totalSamples} samples · ${report.configuration.calibrationPolicy.label} · configured offset ${report.configuration.captionOffsetMs} ms", style = MaterialTheme.typography.bodySmall)
        for (backend in report.configuration.plannedBackendOrder) {
            val rows = report.playbackResults.filter { it.requestedBackend == backend }
            var expanded by remember(report.startedAtMillis, backend) { mutableStateOf(false) }
            if (rows.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${backend.label} · ${rows.size} observed samples", style = MaterialTheme.typography.titleSmall)
                        Text("${rows.count { it.failure != null }} failures · ${rows.sumOf { it.bufferRestarts }} restarts · ${rows.count { it.cleanupState == "PENDING" }} pending cleanup", style = MaterialTheme.typography.bodySmall)
                        Text(rows.groupingBy { it.quality }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key.name.lowercase().replace('_', ' ')}" }, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide samples" else "Inspect samples and causes") }
                    }
                }
                if (expanded) rows.forEach { VoiceSpeechPlaybackResultCard(it) }
            }
        }
    } else {
        Text("${report.configuration.workload.label} · ${report.completedSamples}/${report.totalSamples} fixture iterations · ${report.completedChunks} native chunks", style = MaterialTheme.typography.bodySmall)
        for (result in report.results) VoiceSpeechBenchmarkResultCard(result)
    }
    Text(
        if (report.configuration.playback) {
            "Presented audio uses the production AudioTrack timestamp or playback head. Caption checks validate that clock, not perceptual word accuracy. Independent fixture manifests detect missing caption sections. Acoustic speaker latency, acoustic word alignment, CPU/GPU/NPU utilization and battery energy remain unmeasured."
        } else {
            "First PCM is the first complete native chunk; total synthesis includes every chunk. Playback and acoustic latency, underruns and battery energy are unmeasured. First use retains the phone's file cache. Medians average the middle pair; p95 is withheld below twenty samples."
        },
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
private fun VoiceSpeechPlaybackResultCard(result: VoiceSpeechPlaybackResult) {
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${result.requestedBackend.label} · ${result.scenario.label} · sample ${result.repeat + 1}${if (result.warmup) " · warm-up" else ""}", style = MaterialTheme.typography.titleSmall)
            Text("${result.state.name.lowercase()} · ${result.quality.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
            result.failure?.let { Text("${it.name.lowercase().replace('_', ' ')}${result.failureStage?.let { stage -> " · ${stage.name.lowercase()}" }.orEmpty()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            result.pipelineFailureCode?.let { Text("Playback cause: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result.nativeCode?.let { Text("Native code: $it · requested ${result.failedRequestedProvider ?: "unknown"} · observed ${result.failedEffectiveProvider ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
            result.failedChunk?.let { Text("Failed chunk ${it + 1} · input ${result.failedInputBytes ?: "unknown"}/${result.failedInputByteLimit ?: "unknown"} bytes · tokens ${result.failedTokenCount ?: "unknown"}/${result.failedTokenLimit ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
            result.timeoutLimitMs?.let { Text("Deadline: ${result.timeoutScope ?: "unknown"} · ${result.timeoutElapsedMs ?: "unknown"}/$it ms", style = MaterialTheme.typography.bodySmall) }
            Text("First PCM ${result.firstPcmMs ?: "—"} ms · queued ${result.firstQueuedPcmMs ?: "—"} ms · presented ${result.firstPresentedAudioMs ?: "—"} ms", style = MaterialTheme.typography.bodySmall)
            Text("AudioTrack write ${result.firstAudioTrackWriteMs ?: "unmeasured"} ms · play requested ${result.trackPlayRequestedMs ?: "unmeasured"} ms · model preparation ${result.sessionPrepareMs ?: "unmeasured"} ms", style = MaterialTheme.typography.bodySmall)
            Text("Requested/effective threads ${result.requestedThreads ?: "unknown"}/${result.effectiveThreads ?: "unknown"} · engine reused ${result.engineReused ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
            Text("${result.validatedChunks}/${result.chunks} chunks verified · synthesis ${result.totalSynthesisMs} ms · audio ${result.totalAudioMs} ms · elapsed ${result.elapsedMs} ms", style = MaterialTheme.typography.bodySmall)
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
