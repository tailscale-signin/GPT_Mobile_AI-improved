package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.assist.AssistProcessingPermission
import dev.chungjungsoo.gptmobile.data.assist.processingPermission
import dev.chungjungsoo.gptmobile.data.model.ClientType

/** The single editing surface; profile assignment and search focus this same card. */
@Composable
internal fun LocalAssistCard(viewModel: LocalRuntimeSettingsViewModel, onMarketplace: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val readiness by dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val policy = settings.localAssistPolicy
    val selected = profiles.firstOrNull { it.uid == policy.profileUid }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    SettingsPanel("Local Assist") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Use Local Assist", Modifier.weight(1f))
            Switch(settings.localAssist, { viewModel.updateTuning(assist = it) }, enabled = !busy)
        }
        Text("Use a selected local model to help prepare research and source material.", style = MaterialTheme.typography.bodySmall)
        Column {
            TextButton(onClick = { choosing = true }, enabled = !busy) {
                Text(selected?.name ?: if (policy.profileUid != null) "Unavailable helper (${policy.profileUid})" else "Choose helper")
            }
            DropdownMenu(choosing, { choosing = false }) {
                profiles.filter { it.compatibleType == ClientType.LITERT_LM }.forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.name + if (!profile.enabled) " · disabled" else "") }, onClick = {
                        viewModel.updateAssist { it.copy(profileUid = profile.uid) }
                        choosing = false
                    })
                }
            }
        }
        val status = when {
            !settings.localAssist -> "Off"
            policy.profileUid == null -> "Choose a helper"
            selected == null || !selected.enabled -> "Model unavailable"
            policy.processingPermission(selected) != AssistProcessingPermission.ALLOWED -> "Setup required"
            readiness.profileUid != selected.uid -> "Setup required · verify the selected model"
            else -> readiness.reason
        }
        Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        if (settings.localAssist && selected != null && policy.processingPermission(selected) != AssistProcessingPermission.ALLOWED) {
            TextButton(onClick = { reviewing = true }) { Text("Review setup") }
        } else if (settings.localAssist && selected != null) {
            TextButton(onClick = viewModel::testLocalAssist, enabled = !busy) { Text("Verify model") }
        } else if (settings.localAssist && selected == null && policy.profileUid != null) {
            TextButton(onClick = onMarketplace) { Text("Verify installed model") }
        }
    }
    if (reviewing && selected != null) {
        AlertDialog(
            onDismissRequest = { reviewing = false },
            title = { Text("Allow ${selected.name} to help locally?") },
            text = { Text("This helper may process the current task and authorized source material on this device. It may prepare while the app is open and idle. Remote replies continue when it is unavailable. Cloud, memory and tool permissions remain separate.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateAssist { it.copy(permissionVersion = 1, profilePermissions = it.profilePermissions + (selected.uid to AssistProcessingPermission.ALLOWED), preloadInForeground = true) }
                    reviewing = false
                }) { Text("Allow preparation") }
            },
            dismissButton = { TextButton(onClick = { reviewing = false }) { Text("Cancel") } }
        )
    }
}
