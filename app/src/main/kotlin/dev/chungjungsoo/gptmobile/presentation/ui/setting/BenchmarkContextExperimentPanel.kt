package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextArmMetrics
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextExperiment
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkContextComparisons

@Composable
internal fun BenchmarkContextExperimentControls(profileName: String?, enabled: Boolean, busy: Boolean, taskCount: Int, repeats: Int, start: (BenchmarkContextExperiment) -> Unit) {
    var candidate by rememberSaveable { mutableStateOf("TASK") }
    var all by rememberSaveable { mutableStateOf(false) }
    val variants = if (all) BenchmarkContextExperiment.VARIANTS else listOf("CURRENT", candidate)
    val experiment = remember(variants) { BenchmarkContextExperiment(variants) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Compare repository context", style = MaterialTheme.typography.titleLarge)
            Text("Use ${profileName ?: "Profile A"} for every arm. Source, tasks, generation settings and repair limits stay fixed. The same public starting files are supplied to each arm.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("REFERENCE" to "Reference document", "TASK" to "Task context", "SEMANTIC" to "Local semantic ranking").forEach { (value, label) ->
                    FilterChip(candidate == value, { candidate = value }, label = { Text(label) }, enabled = !busy && !all)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(all, { all = it }, enabled = !busy)
                Text("Compare all four variants", Modifier.padding(top = 12.dp))
            }
            Text("${variants.joinToString(" / ")} · ${taskCount * repeats * variants.size} task episodes · alternating arm order", style = MaterialTheme.typography.bodySmall)
            Text("Supplement budget: ${benchmarkContextBudget(experiment)} · frozen when this experiment starts", style = MaterialTheme.typography.bodySmall)
            Text("Measures added context and local cold/warm source-cache preparation. Semantic ranking uses the bundled local encoder when available; a fallback is reported explicitly. Hidden tests and reference repairs never enter retrieval.", style = MaterialTheme.typography.bodySmall)
            Button({ start(experiment) }, Modifier.fillMaxWidth(), enabled = enabled) { Text("Start context experiment") }
        }
    }
}

@Composable
internal fun BenchmarkContextExperimentResults(runs: List<BenchmarkRun>) {
    val comparisons = remember(runs) { benchmarkContextComparisons(runs) }
    comparisons.take(12).forEach { comparison ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${comparison.profileName} · CURRENT / ${comparison.variant}", style = MaterialTheme.typography.titleLarge)
                Text(comparison.state.replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                Text(comparison.reason, style = MaterialTheme.typography.bodySmall)
                Text("${comparison.matchedPairs}/${comparison.plannedPairs} matched tasks · ${comparison.independentFamilies} independent families")
                Text("Completion difference: ${contextNumber(comparison.differencePercentagePoints)} percentage points; 95% family interval ${contextNumber(comparison.lower95)} to ${contextNumber(comparison.upper95)}", style = MaterialTheme.typography.bodySmall)
                ContextArmDetails(comparison.baseline)
                HorizontalDivider()
                ContextArmDetails(comparison.candidate)
                Text("Source coverage counts declared public fixture files. Relevant-file and test recall remain unknown without independent annotations. Unknown usage or prices remain unknown; preparation speed alone does not prove better coding.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ContextArmDetails(arm: BenchmarkContextArmMetrics) {
    Text("${arm.variant}: ${arm.completedTasks}/${arm.verifiedTasks} independently evaluable tasks passed", style = MaterialTheme.typography.titleSmall)
    Text("Context ${contextNumber(arm.medianContextBytes)} bytes · local preparation cold ${contextNumber(arm.medianColdPreparationMs)} ms / warm ${contextNumber(arm.medianWarmPreparationMs)} ms", style = MaterialTheme.typography.bodySmall)
    Text("Reads ${arm.sourceReads} · warm cache hits ${arm.warmCacheHits} · repeated reads ${arm.repeatedSourceReads} · public source coverage ${contextNumber(arm.declaredSourceCoverage?.times(100))}%", style = MaterialTheme.typography.bodySmall)
    Text("All-in input/output tokens: ${arm.inputTokens ?: "Unknown"} / ${arm.outputTokens ?: "Unknown"}; elapsed ${contextNumber(arm.elapsedMs?.div(1000.0))} s", style = MaterialTheme.typography.bodySmall)
    Text("First verified patch: ${contextNumber(arm.medianTimeToFirstVerifiedPatchMs)} ms median among successful tasks. API cost estimate: ${if (arm.estimatedApiCostMicros != null && arm.currency != null) "${arm.currency} ${"%.6f".format(arm.estimatedApiCostMicros / 1_000_000.0)}" else "Unknown"}", style = MaterialTheme.typography.bodySmall)
    Text("Completion evidence contradictions: ${arm.completionEvidenceContradictions}. False completion claims: ${arm.falseCompletionCount ?: "Not assessed by structured patch tasks"}", style = MaterialTheme.typography.bodySmall)
    Text("Effective retrieval: ${arm.retrievalModes.joinToString().ifBlank { "Not measured" }} · unbound or unexecuted receipts ${arm.staleOrUnboundReceipts}", style = MaterialTheme.typography.labelSmall)
}

private fun contextNumber(value: Double?): String = value?.let { "%.1f".format(it) } ?: "Unknown"
