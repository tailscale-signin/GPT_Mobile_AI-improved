package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationObservation

@Composable
internal fun GatewayConversationStatusCard(observation: GatewayConversationObservation, reconnect: () -> Unit, stop: () -> Unit) {
    val job = observation.currentJob
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(gatewayConversationStatus(observation), style = MaterialTheme.typography.titleSmall)
            if (job != null) {
                Text(job.title, style = MaterialTheme.typography.bodyMedium)
                val detail = listOfNotNull(
                    job.phase.replace('_', ' ').takeIf { it.isNotBlank() },
                    job.queuePosition?.let { "Queue position $it" },
                    job.profileName.takeIf { it.isNotBlank() },
                    job.repository,
                    job.branch
                ).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                job.historyLimitation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                if (job == null && observation.submissionState == "REJECTED") {
                    "The gateway rejected this submission. Review the reported issue; its original request remains saved in Jobs."
                } else if (job == null) {
                    "Your request is saved. Reconnect checks whether the gateway has accepted it."
                } else if (observation.stale && !job.terminal) {
                    "The gateway has not confirmed its latest state. Reconnect to retrieve the saved conversation."
                } else if (!job.terminal) {
                    "You can leave Chat and return to this work. Use Stop job to request cancellation."
                } else {
                    "The saved result belongs to this conversation."
                },
                style = MaterialTheme.typography.bodySmall
            )
            observation.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = reconnect) { Text("Reconnect") }
                if (job?.terminal == false && "cancel" in job.permittedActions) TextButton(onClick = stop) { Text("Stop job") }
            }
        }
    }
}

internal fun gatewayConversationStatus(observation: GatewayConversationObservation): String {
    val job = observation.currentJob ?: return when (observation.submissionState) {
        "PREPARED", "SUBMITTING" -> "Submitting saved request"
        "REJECTED" -> "Submission needs attention"
        "CANCEL_REQUESTED" -> "Cancellation requested"
        else -> "Confirming submission"
    }
    if (observation.submissionState == "CANCEL_REQUESTED" && !job.terminal) return "Cancellation requested · awaiting gateway confirmation"
    val state = when (job.schedulingState) {
        "queued" -> "Queued"
        "running" -> "Running"
        "waiting_for_client_tool", "waiting_for_device" -> "Waiting for this device"
        "waiting_for_input", "waiting_for_approval", "needs_attention" -> "Needs attention"
        "waiting" -> when (job.phase.lowercase(java.util.Locale.ROOT)) {
            "waiting_for_client_tool", "waiting_for_device" -> "Waiting for this device"
            "ci" -> "Waiting for CI"
            "merge_ready" -> "Waiting for approval"
            else -> "Needs attention"
        }
        "completed" -> "Completed"
        "cancelled" -> "Cancellation confirmed"
        "interrupted" -> "Interrupted"
        "failed" -> "Failed"
        else -> "Saved work"
    }
    return if (observation.stale && !job.terminal) "Last seen: $state" else "$state on gateway"
}
