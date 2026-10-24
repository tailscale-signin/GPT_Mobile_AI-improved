package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.ResearchProfileStatistics

/** Debug-only derived records; provider completion and accepted Research stages stay distinct. */
@Composable
internal fun ResearchProfileStatisticsPanel(statistics: List<ResearchProfileStatistics>, profileNames: Map<String, String>) {
    if (statistics.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI profile statistics", style = MaterialTheme.typography.titleSmall)
        statistics.forEach { stats ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profileNames[stats.profileUid] ?: stats.profileUid, style = MaterialTheme.typography.labelLarge)
                Text("${stats.sources} sources · ${stats.uniqueSources} unique · ${stats.sourcesRead} read · ${stats.studiesExamined} examined", style = MaterialTheme.typography.bodySmall)
                Text("${stats.modelRequests} model invocations · ${stats.completedRequests} completed · ${stats.failedRequests} failed · ${stats.runningRequests} running · ${stats.cancelledRequests} stopped", style = MaterialTheme.typography.bodySmall)
                Text("Retained stages: ${stats.acceptedStages} accepted · ${stats.failedStages} failed · ${stats.skippedStages} skipped", style = MaterialTheme.typography.bodySmall)
                Text("${stats.inputTokens} input / ${stats.outputTokens} output ${if (stats.estimatedTokens) "tokens (estimated)" else "tokens"} · ${stats.modelDurationMs / 1000}s model time", style = MaterialTheme.typography.bodySmall)
                stats.lastStage?.let { Text("$it · ${stats.lastStageOutcome.orEmpty()}", style = MaterialTheme.typography.labelSmall) }
                stats.lastFailureCategory?.let { Text("Last issue: $it", style = MaterialTheme.typography.labelSmall) }
                stats.lastInvocationId?.let { Text("Invocation: $it", style = MaterialTheme.typography.labelSmall) }
                if (stats.examinationsPending > 0) Text("${stats.examinationsPending} examinations pending", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
