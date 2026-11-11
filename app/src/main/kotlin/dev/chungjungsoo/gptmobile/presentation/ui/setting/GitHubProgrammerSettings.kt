package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionMode
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun GitHubProfilePicker(profiles: List<PlatformV2>, selectedUid: String?, onSelect: (String?) -> Unit, global: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.uid == selectedUid }
    Column {
        Text(if (global) "Default GitHub AI profile" else "Engineering AI profile", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("github_main_profile")) {
            Text(profiles.firstOrNull { it.uid == selectedUid }?.name?.take(120) ?: if (selectedUid == null) "Choose an AI profile" else "Selected profile unavailable", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.filter { it.enabled }.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name.take(120), maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                    expanded = false
                    onSelect(profile.uid)
                })
            }
        }
        selected?.let { profile ->
            Text("Requested model: ${profile.model.ifBlank { "Not configured" }}", style = MaterialTheme.typography.bodySmall)
            val location = if (profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM) "On this device" else "Observed when the selected endpoint responds"
            Text("Execution location: $location", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GitHubProgrammerSettings(workspace: GitHubWorkspaceState, state: GitHubProgrammerState, onProfile: (String?) -> Unit, onIndex: (Boolean) -> Unit, onPrepare: () -> Unit, onSettings: (dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride) -> Unit = {}, onExport: () -> Unit = {}, onOpenRepositoryMap: (() -> Unit)? = null, onDecisionMode: (GitHubDecisionMode) -> Unit = {}) {
    var inspection by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    inspection?.let { (profileUid, engineering) ->
        ConnectionDoctorDialog(onDismiss = { inspection = null }, initialProfileUid = profileUid, engineeringGateway = engineering)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitHubProfilePicker(state.profiles, state.selectedProfileUid ?: state.profileUid ?: state.profileOverrideUid, onProfile)
        Text("This profile handles GitHub work and review. If it is unavailable, your task pauses until you explicitly select a profile.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { state.profileUid?.let { inspection = it to false } }, enabled = state.profileUid != null, modifier = Modifier.testTag("github_test_connection")) { Text("Test AI connection") }
        onOpenRepositoryMap?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().testTag("github_repository_map")) { Text("Repository map") }
        }
        var gatewayMenu by remember { mutableStateOf(false) }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Engineering gateway", style = MaterialTheme.typography.titleMedium)
                Text("Select a private gateway for saved repository jobs and graphs. Conversation and repository questions use the selected AI profile. Use Jobs to start saved work explicitly.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { gatewayMenu = true }, enabled = state.profileUid != null) {
                    Text(state.profiles.firstOrNull { it.uid == state.profileSettings.engineeringGatewayProfileUid }?.name ?: "Select gateway connection")
                }
                DropdownMenu(gatewayMenu, { gatewayMenu = false }) {
                    DropdownMenuItem(text = { Text("Unavailable / not configured") }, onClick = {
                        gatewayMenu = false
                        onSettings(state.profileSettings.copy(engineeringGatewayProfileUid = null))
                    })
                    state.profiles.filter { dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy.supportsSavedJobs(it) }.forEach { gateway ->
                        DropdownMenuItem(text = { Text(gateway.name) }, onClick = {
                            gatewayMenu = false
                            onSettings(state.profileSettings.copy(engineeringGatewayProfileUid = gateway.uid))
                        })
                    }
                }
                Text("Add a separate Llama or Ollama connection in Platforms using the desktop gateway address, then select it here.", style = MaterialTheme.typography.bodySmall)
                Text("Gateway execution mode", style = MaterialTheme.typography.titleSmall)
                var batchModeOpen by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { batchModeOpen = true },
                    enabled = state.profileUid != null && state.profileSettings.engineeringGatewayProfileUid != null,
                    modifier = Modifier.testTag("github_batch_mode")
                ) {
                    Text(
                        when (state.profileSettings.executionBatchMode) {
                            "smart" -> "Smart Batch"
                            "full" -> "Full Integration Batch"
                            else -> "Standard"
                        }
                    )
                }
                DropdownMenu(expanded = batchModeOpen, onDismissRequest = { batchModeOpen = false }) {
                    listOf("standard" to "Standard", "smart" to "Smart Batch", "full" to "Full Integration Batch").forEach { (mode, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                batchModeOpen = false
                                onSettings(state.profileSettings.copy(executionBatchMode = mode))
                            }
                        )
                    }
                }
                Text(
                    "Full Batch requires an explicitly enabled Windows Gateway. This changes only newly admitted jobs, not saved work. GitHub permissions and merge approvals are unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GitHubGatewayConnectionCheck(
                    dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile(state.profiles, state.profileSettings.engineeringGatewayProfileUid)?.uid
                ) { inspection = it to true }
            }
        }
        GitHubCreativitySettings(state.profileSettings.sampling, state.profileUid != null) {
            onSettings(state.profileSettings.copy(sampling = it))
        }
        GitHubProfileOverrides(state.profileSettings, state.profileUid != null, state.profiles.firstOrNull { it.uid == state.profileUid }?.maxToolCalls, onSettings)
        GitHubWorkflowSettings(state.profileSettings.quickWorkflows, state.profileUid != null) {
            onSettings(state.profileSettings.copy(quickWorkflows = it))
        }
        OutlinedButton(onClick = onExport, enabled = state.chatId?.let { it > 0 } == true, modifier = Modifier.fillMaxWidth().testTag("github_export_conversation")) {
            Text("Export conversation")
        }
        Text("Save this GitHub conversation as Markdown or plain text. Debug mode also offers a diagnostic ZIP.", style = MaterialTheme.typography.bodySmall)
        state.chatId?.takeIf { it > 0 }?.let { dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubEvidenceButton(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        GitHubDecisionSettings(state.decisionMode, state.chatId?.let { it > 0 } == true, onDecisionMode)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prepare code context", style = MaterialTheme.typography.titleSmall)
                Text("Optional incremental indexing. Files remain available on demand.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(state.prepareIndex, onIndex, modifier = Modifier.testTag("github_index_option"))
        }
        workspace.indexStatus?.let { status ->
            val indexed = status["indexed_blobs"]?.jsonPrimitive?.intOrNull ?: 0
            val total = status["total_paths"]?.jsonPrimitive?.intOrNull
            Text(if (total == null) "$indexed files cached. Coverage is checked against the selected revision." else "$indexed of $total files cached. Partial indexing remains visible.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.prepareIndex) OutlinedButton(onClick = onPrepare, enabled = !workspace.busy) { Text("Prepare next batch") }
    }
}

@Composable
private fun GitHubDecisionSettings(mode: GitHubDecisionMode, enabled: Boolean, onChange: (GitHubDecisionMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun label(value: GitHubDecisionMode) = when (value) {
        GitHubDecisionMode.ASK -> "Ask each time"
        GitHubDecisionMode.SUGGESTED -> "Use suggested choices"
        GitHubDecisionMode.AUTO_APPROVE -> "Auto-approve eligible commits"
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat decisions", style = MaterialTheme.typography.titleMedium)
            Text("Applies to this conversation and repository.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("github_decision_mode")) { Text(label(mode)) }
            DropdownMenu(menu, { menu = false }) {
                GitHubDecisionMode.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                        menu = false
                        onChange(option)
                    })
                }
            }
            Text(
                when (mode) {
                    GitHubDecisionMode.ASK -> "Review each patch and choose how to answer each question."
                    GitHubDecisionMode.SUGGESTED -> "Use the suggested option when one is available. Review proposed commits yourself."
                    GitHubDecisionMode.AUTO_APPROVE -> "Use suggested choices and approve eligible working-branch patches through saved task recovery. Deletions, workflow changes, and changes that require hardware verification still ask."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun GitHubGatewayConnectionCheck(profileUid: String?, onCheck: (String) -> Unit) {
    OutlinedButton(onClick = { profileUid?.let(onCheck) }, enabled = profileUid != null, modifier = Modifier.testTag("github_check_gateway")) {
        Text("Check gateway readiness")
    }
    Text("Engineering jobs use GitHub credentials saved on the gateway computer. Its enabled GitHub connection is selected automatically when it is the only eligible connection.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun GitHubProfileOverrides(value: dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride, enabled: Boolean, configuredCallLimit: Int?, onChange: (dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var workflowMenu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GitHub chat preferences", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Saved separately for this AI profile in GitHub chats.", style = MaterialTheme.typography.bodySmall)
            Column {
                Text("Workflow", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { workflowMenu = true }, enabled = enabled, modifier = Modifier.testTag("github_workflow_preset")) {
                    Text(dev.chungjungsoo.gptmobile.data.github.GitHubWorkflowPreset.selected(value.workflowPreset).title)
                }
                DropdownMenu(expanded = workflowMenu, onDismissRequest = { workflowMenu = false }) {
                    dev.chungjungsoo.gptmobile.data.github.GitHubWorkflowPreset.all.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.title) }, onClick = {
                            workflowMenu = false
                            onChange(value.copy(workflowPreset = preset.id))
                        })
                    }
                }
                val preset = dev.chungjungsoo.gptmobile.data.github.GitHubWorkflowPreset.selected(value.workflowPreset)
                val effective = configuredCallLimit?.let { dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLimits.callLimit(preset.prompt(), it, preset) }
                Text(
                    if (effective == null) "Select a profile to see the workflow allowance. The preset ceiling is ${preset.toolCalls} tool calls." else "Workflow allowance: ${effective.description()}. Preset ceiling: ${preset.toolCalls}. Your task or chat allowance can lower this; the run records its accepted limit before starting.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            GitHubOverrideSwitch("Web search", "Search the web and read source pages.", value.webSearch, enabled) { onChange(value.copy(webSearch = it)) }
            GitHubOverrideSwitch("GitHub tools", "Use the connected repository and review changes before committing.", value.githubTools, enabled) { onChange(value.copy(githubTools = it)) }
            GitHubOverrideSwitch("Maximum context expansion", "Expand up to the provider or local model capacity, with room for results and review.", value.maximumContext, enabled) { onChange(value.copy(maximumContext = it)) }
            Text("Engineering memory", style = MaterialTheme.typography.titleSmall)
            dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryMode.entries.forEach { mode ->
                TextButton(onClick = { onChange(value.copy(engineeringMemory = mode)) }, enabled = enabled) {
                    Text(
                        (
                            when (mode) {
                                dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryMode.SESSION_ONLY -> "Session only"
                                dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryMode.PERSISTENT -> "Persistent repository memory"
                                dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryMode.HYBRID -> "Hybrid semantic + evidence memory"
                            }
                            ) + if (value.engineeringMemory == mode) " ✓" else ""
                    )
                }
            }
            GitHubOverrideSwitch("Allow cloud engineering recall", "Permit this profile to receive historical repository memory. Off keeps recalled source and decisions on private destinations.", value.allowCloudEngineeringMemory, enabled) { onChange(value.copy(allowCloudEngineeringMemory = it)) }
            Column {
                Text("Engineering budget", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("github_engineering_budget")) { Text(engineeringLabel(value.engineeringMode)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(dev.chungjungsoo.gptmobile.data.context.EngineeringMode.ULTRA_MAX, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.ENGINEERING_MAX, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.DEEP, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.STANDARD, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.QUICK).forEach { mode ->
                        DropdownMenuItem(text = { Text(engineeringLabel(mode)) }, onClick = {
                            menu = false
                            onChange(value.copy(engineeringMode = mode))
                        })
                    }
                }
            }
            Text("Ultra Maximum is the default. GitHub work allows up to 512 tool calls; saved gateway jobs request up to 24 hours and 512 model calls. Your saved limits still apply, and each job shows the allowance accepted by its gateway.", style = MaterialTheme.typography.bodySmall)
            Text("Llama gateway · Review required before the final answer. Design decisions appear as buttons, with a custom answer option.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun engineeringLabel(mode: dev.chungjungsoo.gptmobile.data.context.EngineeringMode): String = when (mode) {
    dev.chungjungsoo.gptmobile.data.context.EngineeringMode.ULTRA_MAX -> "Ultra Maximum"
    else -> mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun GitHubOverrideSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
