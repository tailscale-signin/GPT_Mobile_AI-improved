package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord
import dev.chungjungsoo.gptmobile.data.research.ResearchNextAction

/** An immutable view of the attempt the user actually reviewed, never a remote receipt. */
internal data class ResearchInterruptedReview(
    val execution: ResearchExecutionRecord,
    val actionId: String,
    val requests: List<ResearchInterruptedRequest>,
    val retainedDeliveryStatuses: List<String>,
    val retainedReplyCount: Int
) {
    companion object {
        fun capture(
            execution: ResearchExecutionRecord,
            actionId: String,
            runs: Map<String, AgentRun>,
            profileNames: Map<String, String>,
            replies: List<MessageV2>
        ): ResearchInterruptedReview {
            require(execution.attempt.state.terminal && execution.attempt.nextAction == ResearchNextAction.RECONCILE_REQUESTS)
            require(actionId.isNotBlank())
            val receipts = execution.receipts.filter { it.attemptId == execution.attempt.attemptId && it.generation == execution.attempt.generation }
            val requests = execution.attempt.dispatchedRunIds.sorted().map { id ->
                val run = runs[id]?.takeIf { it.chatId == execution.accepted.chatId && it.userMessageId == execution.accepted.originatingUserMessageId }
                val runReceipts = receipts.filter { it.runId == id }
                val profiles = (listOfNotNull(run?.profileUid) + runReceipts.mapNotNull { it.profileUid }).distinct()
                ResearchInterruptedRequest(
                    runId = id,
                    profiles = profiles.map { uid -> profileNames[uid]?.takeIf { it.isNotBlank() } ?: uid }.ifEmpty { listOf("Profile record unavailable") },
                    localOutcome = run?.status ?: "Local run record unavailable",
                    lastRecordedPhase = runReceipts.lastOrNull()?.state?.name,
                    gatewayJobId = run?.gatewayJobId
                )
            }
            val turnReplies = replies.filter { it.chatId == execution.accepted.chatId && it.linkedMessageId == execution.accepted.originatingUserMessageId }
            val deliveries = turnReplies.mapNotNull { reply ->
                reply.effectiveTimeline().lastOrNull { it.researchDeliveryReceipt != null }?.researchDeliveryReceipt?.let { receipt ->
                    receipt.displayValidityFor(reply.effectiveContent())?.let { it.name to receipt.revisionId }
                }
            }.distinctBy { it.second }.map { it.first }
            return ResearchInterruptedReview(execution, actionId, requests, deliveries, turnReplies.count { it.effectiveContent().isNotBlank() })
        }
    }
}

internal data class ResearchInterruptedRequest(
    val runId: String,
    val profiles: List<String>,
    val localOutcome: String,
    val lastRecordedPhase: String?,
    val gatewayJobId: String?
) {
    // Neither a local terminal status nor a gateway job ID confirms upstream acceptance.
    val remoteAcceptance: String get() = "UNKNOWN"
}

@Composable
internal fun ResearchInterruptedRequestsDialog(
    review: ResearchInterruptedReview,
    onDismiss: () -> Unit,
    onAuthorizeNewAttempt: (String) -> Unit
) {
    var acknowledged by remember(review.actionId) { mutableStateOf(false) }
    var submitted by remember(review.actionId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review interrupted requests") },
        text = {
            Column(
                Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("These requests were dispatched during the interrupted attempt. Local outcomes do not establish remote acceptance. An earlier request may still complete or incur a charge.")
                review.requests.forEach { request ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(request.profiles.joinToString(", "), style = MaterialTheme.typography.titleSmall)
                        Text("Run: ${request.runId}", style = MaterialTheme.typography.bodySmall)
                        Text("Local outcome: ${request.localOutcome}", style = MaterialTheme.typography.bodySmall)
                        request.lastRecordedPhase?.let { Text("Last recorded phase: $it", style = MaterialTheme.typography.bodySmall) }
                        Text("Remote acceptance: ${request.remoteAcceptance}", style = MaterialTheme.typography.bodySmall)
                        request.gatewayJobId?.let { Text("Saved gateway job: $it. This does not confirm provider acceptance.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("${review.execution.attempt.readableSources} readable sources retained. Saved replies: ${review.retainedReplyCount}.")
                review.execution.attempt.evidenceRevision?.let { Text("Evidence revision: $it", style = MaterialTheme.typography.bodySmall) }
                review.execution.attempt.checkpointId?.let { Text("Saved checkpoint: $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    if (review.retainedDeliveryStatuses.isEmpty()) "No validated delivery receipt is available for the visible replies." else "Retained delivery receipts: ${review.retainedDeliveryStatuses.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Starting a new attempt preserves the earlier records and saved evidence. It may send new requests; unknown earlier outcomes remain unknown.")
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        enabled = !submitted,
                        modifier = Modifier.semantics { contentDescription = "Acknowledge unknown remote outcomes and authorize a new attempt" }
                    )
                    Text("I understand these earlier requests may still complete and authorize a new attempt.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = acknowledged && !submitted,
                onClick = {
                    submitted = true
                    onAuthorizeNewAttempt(review.actionId)
                }
            ) { Text("Start new attempt") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitted) { Text("Keep interrupted") } }
    )
}
