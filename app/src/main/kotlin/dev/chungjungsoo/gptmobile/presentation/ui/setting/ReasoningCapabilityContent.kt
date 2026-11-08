package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode

data class ReasoningCapabilityUiState(val checking: Boolean = false, val observation: ReasoningCapabilityObservation? = null)

@Composable
internal fun ReasoningCapabilityContent(
    mode: ReasoningMode,
    state: ReasoningCapabilityUiState,
    onRecheck: () -> Unit,
    onSwitchToAuto: () -> Unit,
    enabled: Boolean
) {
    val now by dev.chungjungsoo.gptmobile.presentation.common.freeProviderClock()
    val observation = state.observation?.takeIf { now - it.observedAtMillis in 0 until 20_000L }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when {
                state.checking -> "Checking support for this model and route…"
                observation?.status == ReasoningCapabilityStatus.SUPPORTED -> if (mode == ReasoningMode.AUTO) "Auto uses the server’s reasoning default." else "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} is supported by the verified template control."
                observation?.status == ReasoningCapabilityStatus.UNSUPPORTED -> "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} is unsupported by this model and template. The saved setting remains unchanged."
                else -> "Support is unverified. On and Off stay blocked until this exact model and route are qualified."
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (!state.checking && observation?.routeVerified == false) Text("The inference route is not verified as ready.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRecheck, enabled = enabled && !state.checking) { Text("Recheck reasoning support") }
        if (mode != ReasoningMode.AUTO) TextButton(onClick = onSwitchToAuto, enabled = enabled && !state.checking) { Text("Switch to Auto") }
    }
}

data class ReasoningAdmissionNotice(
    val profileUid: String,
    val profileName: String,
    val model: String,
    val requested: ReasoningMode,
    val state: ReasoningCapabilityUiState
)

@Composable
internal fun ReasoningAdmissionDialog(
    notice: ReasoningAdmissionNotice,
    onRecheck: () -> Unit,
    onSwitchToAuto: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${notice.profileName}: reasoning settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model: ${notice.model}", style = MaterialTheme.typography.bodySmall)
                ReasoningCapabilityContent(notice.requested, notice.state, onRecheck, onSwitchToAuto, enabled = true)
                Text("Your draft is kept. Rechecking does not send it. Return to the draft and press Send after repairing the setting.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSettings) { Text("Change profile settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back to draft") } }
    )
}
