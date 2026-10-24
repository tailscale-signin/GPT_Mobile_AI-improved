package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState
import dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord
import dev.chungjungsoo.gptmobile.data.research.ResearchNextAction

/** Renders the durable attempt before a source session or a provider request exists. */
@Composable
internal fun ResearchAttemptProgressCard(
    execution: ResearchExecutionRecord,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onReviewInterruptedRequests: () -> Unit,
    debugMode: Boolean = false,
    profileStatistics: List<dev.chungjungsoo.gptmobile.data.research.ResearchProfileStatistics> = emptyList(),
    profileNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val attempt = execution.attempt
    val title = when (attempt.state) {
        ResearchAttemptState.ACCEPTED -> "Research accepted"
        ResearchAttemptState.BLOCKED_CONFIGURATION -> "Research needs attention"
        ResearchAttemptState.WAITING_RESOURCE -> "Research waiting for resources"
        ResearchAttemptState.SEARCHING -> "Research searching for sources"
        ResearchAttemptState.FETCHING -> "Research reading sources"
        ResearchAttemptState.ANALYZING -> "Research analyzing evidence"
        ResearchAttemptState.SYNTHESIZING -> "Research writing the report"
        ResearchAttemptState.VALIDATING -> "Research checking the report"
        ResearchAttemptState.COMPLETED -> "Research complete"
        ResearchAttemptState.PARTIAL -> "Partial research report delivered"
        ResearchAttemptState.FAILED_NO_EVIDENCE -> "Research could not collect readable evidence"
        ResearchAttemptState.FAILED_DELIVERY -> "Evidence retained · writing needs attention"
        ResearchAttemptState.CANCELLED -> "Research stopped"
        ResearchAttemptState.INTERRUPTED -> "Research interrupted"
    }
    val canRetry = (attempt.state.terminal && attempt.state != ResearchAttemptState.COMPLETED) || attempt.state == ResearchAttemptState.BLOCKED_CONFIGURATION
    val needsReconciliation = attempt.nextAction == ResearchNextAction.RECONCILE_REQUESTS
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val goal = execution.accepted.targetSources.takeIf { it > 0 }
            Text(
                if (goal == null) "${attempt.readableSources} readable sources retained" else "${attempt.readableSources} of $goal readable sources retained",
                style = MaterialTheme.typography.bodyMedium
            )
            if (attempt.state == ResearchAttemptState.WAITING_RESOURCE) {
                Text(
                    if (attempt.blockedReason?.contains("benchmark", ignoreCase = true) == true) "Waiting for the current benchmark trial to finish. Research will continue automatically." else "Waiting for shared resources. Research will continue automatically.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (needsReconciliation) Text("Earlier requests may have been accepted. Review the saved records before authorizing another attempt.", style = MaterialTheme.typography.bodySmall)
            if (needsReconciliation) {
                OutlinedButton(onClick = onReviewInterruptedRequests) { Text("Review interrupted requests") }
            }
            if (debugMode) ResearchProfileStatisticsPanel(profileStatistics, profileNames)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!attempt.state.terminal) OutlinedButton(onClick = onStop) { Text("Stop") }
                if (canRetry) {
                    OutlinedButton(onClick = onRetry, enabled = !needsReconciliation) {
                        Text(if (attempt.nextAction == ResearchNextAction.RETRY_DELIVERY) "Retry writing" else "Retry research")
                    }
                }
            }
        }
    }
}
