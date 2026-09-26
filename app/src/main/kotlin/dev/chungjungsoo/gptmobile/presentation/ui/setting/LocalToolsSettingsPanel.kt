package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.isLocalPlatform

@Composable
fun LocalToolsSettingsPanel(
    initialSection: String? = null,
    viewModel: LocalToolsViewModel = hiltViewModel(),
    memory: FactVaultViewModel = hiltViewModel()
) {
    val vault by memory.vault.collectAsStateWithLifecycle()
    val memoryBusy by memory.busy.collectAsStateWithLifecycle()
    val memoryError by memory.error.collectAsStateWithLifecycle()
    val config by viewModel.delegation.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var showMemory by remember { mutableStateOf(initialSection == "memory") }
    var showDelegation by remember { mutableStateOf(initialSection == "delegation") }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalToolToggle("Local memory capture and recall", vault.enabled, !memoryBusy, memory::setEnabled)
                Text("Built in. Learns supported facts from your messages and recalls relevant facts. Stored encrypted on this phone.", style = MaterialTheme.typography.bodySmall)
                Text(if (vault.settings.allowCloudRecall) "Recall can be included in cloud AI requests. Change this in Configure memory." else "Recall is restricted to local AI platforms.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showMemory = true }) { Text("Configure memory · ${vault.facts.size} facts") }
                memoryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalToolToggle("Model delegation", config.enabled, !busy) { value -> viewModel.update { it.copy(enabled = value) } }
                Text("Optional built-in tool inspired by Houtini LM. Delegate a bounded task to an existing AI profile, using its provider connection and model.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDelegation = !showDelegation }) { Text(if (showDelegation) "Hide configuration" else "Configure delegation") }
                if (showDelegation) {
                    LocalToolToggle("Only local AI platforms", config.localPlatformsOnly, !busy) { value -> viewModel.update { it.copy(localPlatformsOnly = value) } }
                    Text("Local platforms are on-device LiteRT, llama and Ollama. Server profiles still send the task to their configured endpoint. Turn this off to choose other providers.", style = MaterialTheme.typography.bodySmall)
                    Text("Target AI profile", style = MaterialTheme.typography.titleSmall)
                    val eligible = profiles.filter { it.enabled && (!config.localPlatformsOnly || it.compatibleType.isLocalPlatform()) }
                    if (eligible.isEmpty()) Text("Create an enabled AI profile first, or allow other providers.")
                    if (eligible.none { it.uid == config.targetProfileUid }) Text("No available target selected", color = MaterialTheme.colorScheme.error)
                    eligible.forEach { profile ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.targetProfileUid == profile.uid, enabled = !busy, onClick = { viewModel.update { it.copy(targetProfileUid = profile.uid) } }, modifier = Modifier.semantics { contentDescription = "Delegate to ${profile.name}" })
                            Column {
                                Text(profile.name)
                                Text("${profile.compatibleType} · ${profile.model}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    DelegationNumber("Maximum input characters", config.maxInputCharacters, 500..16000, !busy) { value -> viewModel.update { it.copy(maxInputCharacters = value) } }
                    DelegationNumber("Maximum output tokens", config.maxOutputTokens, 64..2048, !busy) { value -> viewModel.update { it.copy(maxOutputTokens = value) } }
                    DelegationNumber("Timeout in seconds", config.timeoutSeconds, 5..40, !busy) { value -> viewModel.update { it.copy(timeoutSeconds = value) } }
                    DelegationNumber("Calls per conversation turn", config.maxCallsPerTurn, 1..3, !busy) { value -> viewModel.update { it.copy(maxCallsPerTurn = value) } }
                    Text("Only the supplied task is forwarded. The delegate receives no app tools or automatic chat history. Self-delegation is blocked. An active on-device model cannot delegate to the same on-device engine; use a server target instead.", style = MaterialTheme.typography.bodySmall)
                    Text("Provider usage or server-side tools may incur costs. Remote gateways must honor tool_choice=none; use a direct inference endpoint for strict isolation.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (showMemory) {
        Dialog(onDismissRequest = { showMemory = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FactVaultScreen(viewModel = memory, onBack = { showMemory = false })
        }
    }
}

@Composable
fun LocalToolConfigurationDialog(section: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = onDismiss) { Text("Close") }
                LocalToolsSettingsPanel(initialSection = section)
            }
        }
    }
}

@Composable
private fun LocalToolToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun DelegationNumber(label: String, value: Int, range: IntRange, enabled: Boolean, save: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val parsed = draft.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = draft, onValueChange = { draft = it.take(6) }, label = { Text(label) }, supportingText = { Text("${range.first}–${range.last}") }, isError = parsed == null || parsed !in range, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        TextButton(enabled = enabled && parsed != null && parsed in range && parsed != value, onClick = { parsed?.let(save) }) { Text("Save") }
    }
}
