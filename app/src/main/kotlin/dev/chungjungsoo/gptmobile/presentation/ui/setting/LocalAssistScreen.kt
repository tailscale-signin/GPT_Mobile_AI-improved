package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    var advanced by rememberSaveable { mutableStateOf(false) }
    var contextText by rememberSaveable(policy.contextTokens) { mutableStateOf(policy.contextTokens.toString()) }
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
        Text("Tasks", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Select relevant evidence", Modifier.weight(1f))
            Switch(policy.evidenceSelection, { value -> viewModel.updateAssist { it.copy(evidenceSelection = value) } }, enabled = !busy)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Summarize retrieved text", Modifier.weight(1f))
            Switch(policy.summarizeRetrievedText, { value -> viewModel.updateAssist { it.copy(summarizeRetrievedText = value) } }, enabled = !busy)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Prepare explicit research", Modifier.weight(1f))
            Switch(policy.researchPreparation, { value -> viewModel.updateAssist { it.copy(researchPreparation = value) } }, enabled = !busy)
        }
        Text("Context", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Auto · conservative package limit", Modifier.weight(1f))
            Switch(policy.contextMode == dev.chungjungsoo.gptmobile.data.assist.AssistContextMode.AUTO, { auto ->
                viewModel.updateAssist { it.copy(contextMode = if (auto) dev.chungjungsoo.gptmobile.data.assist.AssistContextMode.AUTO else dev.chungjungsoo.gptmobile.data.assist.AssistContextMode.MANUAL) }
            }, enabled = !busy)
        }
        if (policy.contextMode == dev.chungjungsoo.gptmobile.data.assist.AssistContextMode.MANUAL) {
            OutlinedTextField(contextText, { contextText = it }, label = { Text("Requested context tokens") }, singleLine = true, isError = contextText.toIntOrNull()?.let { it > 0 } != true)
            TextButton(onClick = { contextText.toIntOrNull()?.takeIf { it > 0 }?.let { tokens -> viewModel.updateAssist { it.copy(contextTokens = tokens) } } }, enabled = !busy && contextText.toIntOrNull()?.let { it > 0 } == true) { Text("Apply context") }
        }
        Text("Package capacity, allocation and task qualification differ. Verify model checks the saved request; it does not qualify extended context or vision.", style = MaterialTheme.typography.bodySmall)
        Text("Privacy", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Share helper-derived evidence with the primary model", Modifier.weight(1f))
            Switch(policy.shareDerivedEvidence, { value -> viewModel.updateAssist { it.copy(shareDerivedEvidence = value) } }, enabled = !busy)
        }
        Text("Image reading and autonomous reads are unavailable until the installed package passes their device qualification. Private query sharing is not granted by enabling Assist.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
        if (advanced) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Prepare helper while foreground and idle", Modifier.weight(1f))
                Switch(policy.preloadInForeground, { value -> viewModel.updateAssist { it.copy(preloadInForeground = value) } }, enabled = !busy)
            }
            Text("Ordinary remote replies do not wait for a cold helper. Local primary execution retains runtime ownership.", style = MaterialTheme.typography.bodySmall)
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
