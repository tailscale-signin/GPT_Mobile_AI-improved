package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.agent.RecoveryBlock

/** Grants remain explicit in the existing connection editor. Retry always revalidates access. */
@Composable
internal fun EvidenceRecoveryDialog(
    block: RecoveryBlock,
    onDismiss: () -> Unit,
    onReviewAccess: () -> Unit,
    onRetry: () -> Unit,
    onWithoutEvidence: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved evidence needs permission") },
        text = {
            Column {
                Text("Review the source and destination permissions, then retry. Completed actions remain saved.")
                Text("Retry without saved evidence opens a fresh conversation using only your original question. Attachments and previous responses are omitted. Current profile permissions still apply.")
                if ("REVIEW_ACCESS" in block.allowedActions) TextButton(onClick = onReviewAccess) { Text("Review grants") }
                if ("START_WITHOUT_SAVED_EVIDENCE" in block.allowedActions) TextButton(onClick = onWithoutEvidence) { Text("Retry without saved evidence") }
            }
        },
        confirmButton = {
            if ("RETRY_AFTER_AUTHORIZED_REVISION" in block.allowedActions) TextButton(onClick = onRetry) { Text("Retry after granting access") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
