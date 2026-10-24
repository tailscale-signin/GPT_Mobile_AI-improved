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
    onDismiss: () -> Unit,
    canCombineAvailable: Boolean = false,
    onCombineAvailable: () -> Unit = {}
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(failures.map { it.retryAfterMillis }) {
        failures.mapNotNull { it.retryAfterMillis }.filter { it > now }.distinct().sorted().forEach { deadline ->
            kotlinx.coroutines.delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            now = System.currentTimeMillis()
        }
    }
    val eligible = failures.filter { it.retryAllowed && (it.retryAfterMillis ?: 0) <= now }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve Combined profiles") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Usable replies are saved. Failed profiles do not discard them.")
                Button(onClick = onCombineAvailable, enabled = canCombineAvailable) { Text("Combine available results") }
                Button(onClick = { eligible.forEach { onRetry(it.userMessageId, it.profileUid) } }, enabled = eligible.isNotEmpty()) { Text("Retry eligible profiles") }
                OutlinedButton(onClick = { failures.filterNot { it.applicationFailure }.map { it.profileUid }.distinct().forEach(onRemove) }, enabled = failures.any { !it.applicationFailure }) { Text("Remove failed profiles from this chat") }
                failures.forEach { failure ->
                    var details by remember(failure.attemptId, failure.profileUid) { mutableStateOf(false) }
                    Text(failure.profileName, style = MaterialTheme.typography.titleSmall)
                    Text(failure.model, style = MaterialTheme.typography.labelMedium)
                    Text(failure.reason, color = MaterialTheme.colorScheme.error)
                    if (details) Text(failure.details, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRetry(failure.userMessageId, failure.profileUid) }, enabled = failure in eligible) { Text("Retry profile") }
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
