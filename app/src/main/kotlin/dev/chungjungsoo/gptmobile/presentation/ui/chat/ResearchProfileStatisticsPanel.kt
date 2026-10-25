package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.ResearchProfileStatistics

/** Debug-only retained observations. A stage, an invocation and a transmitted request are distinct. */
@Composable
internal fun ResearchProfileStatisticsPanel(statistics: List<ResearchProfileStatistics>, profileNames: Map<String, String>) {
    if (statistics.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI profile statistics", style = MaterialTheme.typography.titleSmall)
        statistics.forEach { stats ->
            key(stats.profileUid) {
                var expanded by rememberSaveable(stats.profileUid) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profileNames[stats.profileUid] ?: stats.profileUid, style = MaterialTheme.typography.labelLarge)
                    Text("${stats.sources} sources · ${stats.uniqueSources} unique · ${stats.sourcesRead} read · ${stats.studiesExamined} examined", style = MaterialTheme.typography.bodySmall)
                    Text("Retained stages: ${stats.acceptedStages} accepted · ${stats.failedStages} failed · ${stats.skippedStages} skipped", style = MaterialTheme.typography.bodySmall)
                    if (stats.providerWaits > 0) Text("Retained provider waits: ${stats.providerWaits}", style = MaterialTheme.typography.bodySmall)
                    if (stats.examinationsPending > 0) Text("${stats.examinationsPending} examinations pending", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide statistics details" else "Statistics details") }
                    if (expanded) {
                        Text("${stats.modelRequests} invocation records · ${stats.completedRequests} completed · ${stats.failedRequests} failed · ${stats.runningRequests} running · ${stats.cancelledRequests} stopped", style = MaterialTheme.typography.bodySmall)
                        Text("Retained telemetry: ${stats.logicalStages} logical stages · ${stats.physicalGenerations} confirmed network generation requests · ${stats.queuePolls} queue retrievals · ${stats.admissionBlocks} admission blocks", style = MaterialTheme.typography.bodySmall)
                        if (stats.queuedJobs > 0) Text("${stats.queuedJobs} accepted queue jobs · ${stats.cachedQueueResults} cached result reads; repeated job usage is counted once.", style = MaterialTheme.typography.bodySmall)
                        if (stats.unknownTransmissions > 0 || stats.uncorrelatedInvocations > 0 || stats.uncorrelatedStageRecords > 0) {
                            Text("Unconfirmed transport: ${stats.unknownTransmissions}; records without correlation: ${stats.uncorrelatedInvocations} invocations / ${stats.uncorrelatedStageRecords} stages. Missing telemetry is not counted as a transmitted request.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${stats.inputTokens} input / ${stats.outputTokens} output ${if (stats.estimatedTokens) "tokens (includes estimates)" else "tokens"} · ${stats.modelDurationMs / 1000}s model time", style = MaterialTheme.typography.bodySmall)
                        Text("Reported: ${stats.measuredInputTokens} input / ${stats.measuredOutputTokens} output · Estimates / unverified: ${stats.estimatedInputTokens} input / ${stats.estimatedOutputTokens} output", style = MaterialTheme.typography.bodySmall)
                        if (stats.tokenProvenance.isNotEmpty()) Text("Usage sources: ${stats.tokenProvenance.keys.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        stats.reportedReasoningTokens?.let { Text("Reported reasoning tokens: $it", style = MaterialTheme.typography.bodySmall) }
                        stats.visibleOutputEstimate?.let { Text("Visible text estimate: $it tokens; tool-call tokens are unreported.", style = MaterialTheme.typography.bodySmall) }
                        Text("Analyzed sources: ${stats.sourcesFullyAnalyzed} complete / ${stats.sourcesPartiallyAnalyzed} partial · ${stats.sourcesInaccessible} inaccessible", style = MaterialTheme.typography.bodySmall)
                        stats.lastStage?.let { Text("$it · ${stats.lastStageOutcome.orEmpty()}", style = MaterialTheme.typography.labelSmall) }
                        stats.lastFailureCategory?.let { Text("Last issue: $it", style = MaterialTheme.typography.labelSmall) }
                        stats.lastInvocationId?.let { Text("Invocation: $it", style = MaterialTheme.typography.labelSmall) }
                        if (stats.admissionReasons.isNotEmpty()) Text("Admission reasons: ${stats.admissionReasons.entries.joinToString { "${it.key}: ${it.value}" }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
