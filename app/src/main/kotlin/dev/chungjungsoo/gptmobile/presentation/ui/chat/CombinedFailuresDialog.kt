package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CombinedFailuresDialog(
    failures: List<CombinedProfileFailure>,
    onRetry: (Int, String) -> Unit,
    onRemove: (String) -> Unit,
    onCheckJob: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve Combined profiles") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The primary is waiting. Successful replies are saved.")
                failures.forEach { failure ->
                    var cooldownOver by remember(failure.retryAfterMillis) { mutableStateOf((failure.retryAfterMillis ?: 0) <= System.currentTimeMillis()) }
                    androidx.compose.runtime.LaunchedEffect(failure.retryAfterMillis) {
                        kotlinx.coroutines.delay(((failure.retryAfterMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0))
                        cooldownOver = true
                    }
                    var details by remember(failure.attemptId, failure.profileUid) { mutableStateOf(false) }
                    Text(failure.profileName, style = MaterialTheme.typography.titleSmall)
                    Text(failure.model, style = MaterialTheme.typography.labelMedium)
                    Text(failure.reason, color = MaterialTheme.colorScheme.error)
                    if (details) Text(failure.details, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRetry(failure.userMessageId, failure.profileUid) }, enabled = failure.retryAllowed && cooldownOver) { Text("Retry profile") }
                        TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
                    }
                    if (!failure.applicationFailure) {
                        OutlinedButton(onClick = { onRemove(failure.profileUid) }) { Text("Remove from this chat") }
                    }
                    if (failure.attemptId != null && (failure.details.contains("QUEUE_", true) || failure.profileUid.contains("horde", true))) {
                        OutlinedButton(onClick = { onCheckJob(failure.attemptId) }) { Text("Check saved job") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Review later") } }
    )
}
