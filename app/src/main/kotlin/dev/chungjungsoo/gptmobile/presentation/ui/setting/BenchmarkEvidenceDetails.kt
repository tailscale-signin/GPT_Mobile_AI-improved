package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkCleanupMemoryTrend
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPerformanceSummary

@Composable
internal fun BenchmarkIntelligenceHeadline(result: BenchmarkResultPresentation) {
    val summary = result.intelligence?.takeIf { it.plannedQuestions > 0 } ?: return
    val text = when {
        summary.gradedQuestions == 0 -> "Intelligence: Unknown (ungraded) · ${summary.unknownQuestions} questions ungraded"
        !summary.complete -> "Intelligence: Provisional · ${summary.correctQuestions} correct of ${summary.gradedQuestions} graded · ${summary.unknownQuestions} ungraded"
        else -> "Intelligence accuracy: ${benchmarkMetricText(summary.accuracyPercent, "%", 1)} (${summary.correctQuestions}/${summary.gradedQuestions} questions)"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun BenchmarkIntelligenceDetails(result: BenchmarkResultPresentation) {
    val summary = result.intelligence?.takeIf { it.plannedQuestions > 0 } ?: return
    Text("Per-question intelligence", style = MaterialTheme.typography.titleSmall)
    Text("${summary.gradedQuestions}/${summary.plannedQuestions} questions graded · ${summary.unknownQuestions} unknown · ${summary.uniqueQuestions} unique questions · ${summary.uniqueQuestionFamilies} question families", style = MaterialTheme.typography.bodySmall)
    Text("Intelligence format compliance: ${summary.formatPassed}/${summary.formatObserved} observed cases · parseable: ${summary.parseableCases}/${summary.parseabilityObserved} observed cases", style = MaterialTheme.typography.bodySmall)
    Text("Malformed or incomplete output remains ungraded semantically. Diagnostic scenario pass rates and repeated fixtures are separate populations.", style = MaterialTheme.typography.labelSmall)
    summary.domainGradedQuestions.forEach { (domain, count) ->
        Text("${domain.replace('_', ' ')}: ${summary.domains[domain]?.let { benchmarkMetricText(it, "%", 1) } ?: "Unknown / incomplete"} · $count questions graded", style = MaterialTheme.typography.bodySmall)
    }
    Text("Fixture versions: ${summary.fixtureVersions.sorted().joinToString()} · ${summary.policy}", style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun BenchmarkEvidenceDisclosure(title: String, identity: String, content: @Composable () -> Unit) {
    var open by remember(identity) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide $title" else title) }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
internal fun BenchmarkPerformanceDetails(summary: BenchmarkPerformanceSummary?) {
    if (summary == null) return
    BenchmarkEvidenceDisclosure("Performance and comparable history", summary.runId) {
        if (summary.metrics.isEmpty()) Text("No performance distribution is available for this attempt.")
        summary.metrics.forEach { metric ->
            Text(metric.dimension.label, style = MaterialTheme.typography.titleSmall)
            Text("Raw median ${benchmarkMetricText(metric.raw.median, metric.unit)} · p95 ${benchmarkMetricText(metric.raw.p95, metric.unit)} · count ${metric.raw.count}", style = MaterialTheme.typography.bodySmall)
            Text("Raw variance ${benchmarkMetricText(metric.raw.variance, "(${metric.unit})²")} · standard deviation ${benchmarkMetricText(metric.raw.standardDeviation, metric.unit)}", style = MaterialTheme.typography.bodySmall)
            Text("Measurement sources: ${metric.measurementSources.entries.joinToString { "${it.key}: ${it.value}" }.ifBlank { "Not recorded" }}", style = MaterialTheme.typography.labelSmall)
            if (metric.rawObservations.isEmpty()) {
                Text("Observation scope: Not recorded", style = MaterialTheme.typography.labelSmall)
            } else {
                metric.rawObservations.groupBy { it.source to it.scope }.forEach { (provenance, observations) ->
                    Text("Observation source: ${provenance.first} · scope: ${provenance.second} · ${observations.size} cases · native segment counts: ${observations.map { it.segmentCount }.distinct().sorted().joinToString()}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("${metric.repeatCount} repeat positions · ${metric.uniqueFixtureCount} unique fixtures. Repeats of the same fixture are not independent questions.", style = MaterialTheme.typography.labelSmall)
            Text("Qualified median ${benchmarkMetricText(metric.qualified.median, metric.unit)} · p95 ${benchmarkMetricText(metric.qualified.p95, metric.unit)} · count ${metric.qualified.count}", style = MaterialTheme.typography.bodySmall)
            Text("Qualified variance ${benchmarkMetricText(metric.qualified.variance, "(${metric.unit})²")} · ${metric.raw.percentileMethod}", style = MaterialTheme.typography.labelSmall)
            if (metric.exclusions.isNotEmpty()) Text("Qualification exclusions: ${metric.exclusions.entries.joinToString { "${it.key}: ${it.value}" }}", style = MaterialTheme.typography.bodySmall)
            if (metric.previousRunId != null) {
                Text("Previous comparable run: ${metric.previousRunId} · median ${benchmarkMetricText(metric.previousMedian, metric.unit)} · change ${benchmarkMetricDeltaText(metric.changePercent, "%")}", style = MaterialTheme.typography.bodySmall)
                Text("Change uses the same observed context and at least three qualified observations in both runs. A change is descriptive; it does not establish a winner.", style = MaterialTheme.typography.labelSmall)
            } else {
                Text("Previous comparable result: Not measured", style = MaterialTheme.typography.labelSmall)
            }
            Text("Context: ${metric.comparisonContextId}\nSource runs: ${metric.sourceRunIds.joinToString()}\nSource attempts: ${metric.sourceAttemptIds.joinToString().ifBlank { "Not recorded" }}", style = MaterialTheme.typography.labelSmall)
        }
        Text("Thermal states: ${summary.thermalStatuses.sorted().joinToString(transform = ::benchmarkThermalText).ifBlank { "Not measured" }}", style = MaterialTheme.typography.bodySmall)
        Text("Battery: ${summary.batteryBefore?.let { "$it%" } ?: "Not measured"} → ${summary.batteryAfter?.let { "$it%" } ?: "Not measured"}", style = MaterialTheme.typography.bodySmall)
        Text("Peak execution worker PSS: ${benchmarkMetricText(summary.peakWorkerPssBytes?.toDouble()?.div(1024 * 1024), "MiB")} · client PSS: ${benchmarkMetricText(summary.peakClientPssKb?.toDouble()?.div(1024), "MiB")}", style = MaterialTheme.typography.bodySmall)
        Text("Observed runtime versions: ${summary.runtimeVersions.joinToString().ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall)
        Text(summary.limitation, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun BenchmarkCleanupMemoryDetails(trends: List<BenchmarkCleanupMemoryTrend>) {
    if (trends.isEmpty()) return
    BenchmarkEvidenceDisclosure("Memory after cleanup", trends.joinToString { "${it.workerGenerationId}:${it.engineInstanceId}:${it.observations}:${it.lastPssBytes}" }) {
        trends.forEach { trend ->
            Text("${trend.status.replace('_', ' ')} · ${trend.observations} comparable observations", style = MaterialTheme.typography.titleSmall)
            Text("First ${benchmarkMetricText(trend.firstPssBytes?.toDouble()?.div(1024 * 1024), "MiB")} · latest ${benchmarkMetricText(trend.lastPssBytes?.toDouble()?.div(1024 * 1024), "MiB")} · growth ${benchmarkMetricDeltaText(trend.growthBytes?.toDouble()?.div(1024 * 1024), "MiB")}", style = MaterialTheme.typography.bodySmall)
            Text(trend.explanation, style = MaterialTheme.typography.bodySmall)
            Text("Worker: ${trend.workerGenerationId ?: "Unknown"}\nEngine: ${trend.engineInstanceId ?: "Unknown"}\nAttempts: ${trend.sourceAttemptIds.joinToString().ifBlank { "Not recorded" }}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun benchmarkThermalText(value: Int): String = when (value) {
    0 -> "None"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unknown ($value)"
}
