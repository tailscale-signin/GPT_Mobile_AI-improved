package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMetricResult
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.DynamicScoreEngine
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkCoverage

/** The current attempt and any retained qualified population keep separate identities. */
@Composable
internal fun BenchmarkResultCard(result: BenchmarkResultPresentation) {
    val run = result.latestRun
    var expanded by remember(result.identity, run?.id, result.latestQueueItem?.runId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.profileName, style = MaterialTheme.typography.titleMedium)
            Text("Latest attempt: ${result.status.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.titleSmall)
            Text("${run?.model ?: result.latestQueueItem?.profile?.model ?: "No recorded attempt"} · ${benchmarkResultDate(run?.startedAt ?: result.latestQueueItem?.startedAt)}", style = MaterialTheme.typography.labelMedium)
            Text("${result.mode.label} · ${result.executionPolicy?.label ?: "Unknown request policy (historical)"}", style = MaterialTheme.typography.labelSmall)
            BenchmarkRuntimeSummary(run)
            val ranked = result.attemptScore?.takeIf { it.verified && it.overall?.isFinite() == true }
            Text(if (ranked == null) "Provisional · Unranked" else "Verified overall score", style = MaterialTheme.typography.titleSmall)
            Text("Overall: ${ranked?.overall?.let { benchmarkMetricText(it, "/ 100", 1) } ?: "Not available"}", style = MaterialTheme.typography.bodyMedium)
            BenchmarkIntelligenceHeadline(result)
            result.scenarios?.takeIf { it.planned > 0 }?.let { scenarios ->
                Text("Diagnostic scenario pass rate: ${benchmarkMetricText(scenarios.passPercent, "%", 1)} (${scenarios.passed}/${scenarios.graded} graded scenarios; ${scenarios.planned} planned)", style = MaterialTheme.typography.bodySmall)
            }
            if (run != null) {
                val coverage = benchmarkCoverage(run)
                val planned = if (coverage.plannedKnown) coverage.planned.toString() else "unknown"
                Text("${coverage.executed}/$planned planned cases observed executing · ${coverage.accounted} cases accounted for", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Attempt coverage: not recorded", style = MaterialTheme.typography.bodySmall)
            }
            val lastFailure = run?.samples?.lastOrNull { it.failure != null || it.failureCategory != null }
            val failure = result.failure
            if (failure != null || lastFailure != null || run?.stoppedReason != null) {
                Text("Current failure stage: ${failure?.stage ?: lastFailure?.runtimeStage ?: "Not recorded"} · ${failure?.code ?: lastFailure?.failureCategory ?: "No recorded code"}", color = MaterialTheme.colorScheme.error)
                (failure?.message ?: lastFailure?.error ?: run?.stoppedReason)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            result.lastVerified?.let { verified ->
                Text("Last verified result: ${benchmarkResultDate(result.verifiedAt)} · ${verified.resultId}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Advanced evidence") }
            if (expanded) BenchmarkResultDetails(result)
        }
    }
}

@Composable
private fun BenchmarkRuntimeSummary(run: BenchmarkRun?, expanded: Boolean = false) {
    if (run == null) {
        Text("Actual runtime: not recorded", style = MaterialTheme.typography.labelMedium)
        return
    }
    if (!run.local) {
        Text("Remote provider: ${run.provider}", style = MaterialTheme.typography.labelMedium)
        return
    }
    val receipts = run.samples.flatMap { it.runtimeExecutions }
    val loads = receipts.mapNotNull { it.engineLoad }.distinctBy { it.receiptId }
    val requested = loads.map { it.requestedBackend }.distinct().joinToString().ifBlank { run.runtimeSnapshot?.preferredBackend?.name ?: "Not recorded" }
    if (expanded) Text("Requested engine/backend: $requested · profile accelerator ${run.requestedAccelerator ?: "Not recorded"}", style = MaterialTheme.typography.labelMedium)
    val actual = receipts.filter { it.knownDispatched }.mapNotNull { it.engineLoad?.actualBackend }.distinct()
    Text("Actual executed backend: ${actual.joinToString().ifBlank { "Not confirmed by a native receipt" }}", style = MaterialTheme.typography.labelMedium)
    if (expanded && actual.isNotEmpty()) Text("Engine snapshot: ${run.runtimeSnapshot?.backend?.name ?: "Not recorded"}", style = MaterialTheme.typography.labelSmall)
    if (expanded && actual.isEmpty() && (run.backend != null || run.accelerator != null)) Text("Historical runtime report: ${listOfNotNull(run.backend, run.accelerator).joinToString(" / ")}", style = MaterialTheme.typography.bodySmall)
    val versions = loads.map { it.runtimeRevision }.filter { it.isNotBlank() }.distinct()
    if (expanded && versions.isNotEmpty()) Text("Runtime revision: ${versions.joinToString()}", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun BenchmarkResultDetails(result: BenchmarkResultPresentation) {
    val run = result.latestRun
    BenchmarkRuntimeSummary(run, expanded = true)
    run?.let { recorded ->
        val coverage = benchmarkCoverage(recorded)
        Text("Admitted ${coverage.admitted} · terminal observed ${coverage.terminalized} · passed ${coverage.passed} · failed ${coverage.failed} · partial ${coverage.partial} · blocked ${coverage.blocked} · skipped ${coverage.skipped} · pending ${if (coverage.plannedKnown) coverage.pending else "unknown"}", style = MaterialTheme.typography.bodySmall)
        Text("Unknown observations: admission ${coverage.admissionUnknown} · execution ${coverage.executionUnknown} · terminal ${coverage.terminalUnknown}", style = MaterialTheme.typography.bodySmall)
    }
    val metrics = result.attemptMetrics.associateBy { it.id }
    fun ratio(id: String) = metrics[id]?.let { "${benchmarkMetricText(it.value, "%", 1)} (${it.numerator}/${it.denominator})" } ?: "Not measured"
    Text("Answer accuracy ${ratio("answer_correctness")} · delivery ${ratio("delivery_completion")} · planned task success ${ratio("planned_task_success")}", style = MaterialTheme.typography.bodySmall)
    Text("Answer accuracy here follows the retained fixture grade policy. Per-question semantic intelligence is reported separately.", style = MaterialTheme.typography.labelSmall)
    Text("Raw reference decode rate: ${benchmarkMetricText(metrics["raw_reference_decode_rate"]?.value, "reference text units/s", 1)} · first visible text ${benchmarkMetricText(metrics["raw_first_visible_text"]?.value, "ms", 0)}", style = MaterialTheme.typography.bodySmall)
    Text("Raw native rate (model tokenizer): ${benchmarkMetricText(metrics["raw_native_decode_rate"]?.value, "tokens/s", 1)}", style = MaterialTheme.typography.bodySmall)
    val qualified = result.attemptScore?.takeIf { it.verified }
    Text("Qualified timing for this attempt: ${qualified?.speed?.let { benchmarkMetricText(it, "reference text units/s", 1) } ?: "Not qualified"}", style = MaterialTheme.typography.bodySmall)
    if (qualified == null) Text(result.attemptScore?.explanation ?: "Timing qualification is not available for this attempt and measurement policy.", style = MaterialTheme.typography.bodySmall)
    BenchmarkIntelligenceDetails(result)
    BenchmarkPerformanceDetails(result.performance)
    BenchmarkCleanupMemoryDetails(result.cleanupMemory)
    run?.let { BenchmarkConfigurationDetails(it) }
    if (result.attemptMetrics.isEmpty()) Text("No measured metric population is available for this attempt.")
    result.attemptMetrics.forEach { BenchmarkMetricDetails(it) }
    run?.let {
        Text("Attempt: ${it.id}\nConfiguration: ${it.configKey}\nSuite ${it.suiteVersion} · measurement ${it.measurementVersion} · scoring ${it.scoringVersion}", style = MaterialTheme.typography.labelSmall)
        Text("Comparison context: ${DynamicScoreEngine.cohort(it)}", style = MaterialTheme.typography.labelSmall)
        val last = it.samples.lastOrNull()
        last?.let { sample ->
            Text("Latest recorded case: ${sample.label} · ${sample.outcome.name.lowercase()}", style = MaterialTheme.typography.titleSmall)
            sample.validation?.let { validation ->
                fun observation(value: Boolean?) = when (value) {
                    true -> "pass"
                    false -> "fail"
                    null -> "not measured"
                }
                Text("Accepted grade: ${if (validation.gradePassed) "pass" else "fail"} · factual ${observation(validation.factualCorrect)} · units ${observation(validation.unitsCompliant)} · format ${observation(validation.formatCompliant)} · length ${observation(validation.lengthCompliant)} · tool ${observation(validation.toolExecutionSuccessful)}", style = MaterialTheme.typography.bodySmall)
                if (validation.reasons.isNotEmpty()) Text(validation.reasons.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            }
            Text("Native decode rate: ${benchmarkMetricText(sample.nativeMetrics?.takeIf { it.isValid }?.decodeTokensPerSecond, "model tokenizer tokens/s")} · scope ${sample.nativeMetricScope}", style = MaterialTheme.typography.bodySmall)
            Text("Native tokenizer rates describe this runtime measurement; they are separate from reference text units and answer quality.", style = MaterialTheme.typography.labelSmall)
            val cleanup = sample.runtimeCleanup
            Text(
                "Cleanup: ${when {
                    cleanup == null -> "Not recorded"
                    cleanup.safeForSuccessor -> "Confirmed safe for the next request"
                    else -> cleanup.failureReason ?: "Awaiting required confirmation"
                }}",
                style = MaterialTheme.typography.bodySmall
            )
            if (cleanup?.terminationRequested == true) {
                Text(
                    "Worker termination requested · exit ${when (cleanup.workerExitConfirmed) {
                        true -> "confirmed (${cleanup.workerExitEvidence ?: "evidence not labeled"})"
                        false -> "not confirmed"
                        null -> "unknown"
                    }}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (it.mode == BenchmarkMode.CPU_EXPERIMENT) BenchmarkCpuTrialDetails(it)
    }
    result.retainedScore?.takeIf { stored -> stored.sourceRunIds != listOf(run?.id) }?.let { stored ->
        Text("Retained scored population · ${stored.resultId}", style = MaterialTheme.typography.titleSmall)
        Text("These retained source runs have a separate population from the latest attempt: ${stored.sourceRunIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
        stored.metrics.forEach { BenchmarkMetricDetails(it) }
    }
}

@Composable
private fun BenchmarkMetricDetails(metric: BenchmarkMetricResult) {
    Text("${metric.id.replace('_', ' ')}: ${benchmarkMetricText(metric.value, metric.unit)}", style = MaterialTheme.typography.titleSmall)
    val planned = if (metric.plannedKnown) metric.planned.toString() else "not recorded"
    Text("${metric.numerator}/${metric.denominator} · planned $planned · ${metric.population} · ${metric.uniqueFixtureCount} unique fixtures · ${metric.runCount} runs · ${metric.aggregation} · ${metric.policyVersion}", style = MaterialTheme.typography.bodySmall)
    if (metric.sourceCohortIds.isNotEmpty()) Text("Source cohorts: ${metric.sourceCohortIds.joinToString()}", style = MaterialTheme.typography.labelSmall)
    if (metric.exclusions.isNotEmpty()) Text("Excluded: ${metric.exclusions.entries.joinToString { "${it.key.lowercase()} ${it.value}" }}", style = MaterialTheme.typography.bodySmall)
    Text("Runs: ${metric.sourceRunIds.joinToString()}\nAttempts: ${metric.sourceAttemptIds.joinToString().ifBlank { "Historical identifiers unavailable" }}\nMeasured: ${benchmarkResultDate(metric.timestamp)}", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun BenchmarkCpuTrialDetails(run: BenchmarkRun) {
    Text("CPU thread comparison", style = MaterialTheme.typography.titleSmall)
    Text("Device experiments keep cold and cache-hot trials separate and do not change ordinary model quality scores.", style = MaterialTheme.typography.bodySmall)
    run.samples.forEach { sample ->
        val configuration = sample.runtimeExecution?.configuration
        val trial = configuration?.experiment
        Text("${sample.label}: ${sample.outcome.name.lowercase()} · requested threads ${trial?.threadCount ?: "not recorded"} · observed threads ${configuration?.cpuThreads ?: "not recorded"} · ${trial?.cacheRegime ?: "cache regime not recorded"} · reused prompt tokens ${configuration?.reusedPromptTokens ?: "not measured"} · generation ${benchmarkMetricText(sample.generationDurationMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
    }
}
