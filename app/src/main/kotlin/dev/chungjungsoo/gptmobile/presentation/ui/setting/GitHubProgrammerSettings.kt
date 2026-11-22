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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSetting
import dev.chungjungsoo.gptmobile.data.github.GitHubCopilotExecutionOwner
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionMode
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
internal fun GitHubProgrammerSettings(workspace: GitHubWorkspaceState, state: GitHubProgrammerState, onProfile: (String?) -> Unit, onIndex: (Boolean) -> Unit, onPrepare: () -> Unit, onSettings: (dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride) -> Unit = {}, onExport: () -> Unit = {}, onOpenRepositoryMap: (() -> Unit)? = null, onDecisionMode: (GitHubDecisionMode) -> Unit = {}, onCopilotEnabled: (Boolean) -> Unit = {}, onBatchModes: suspend (String, String) -> Set<String> = { _, _ -> emptySet() }, onAdmissionSetting: (suspend (String, String, GatewayAdmissionSetting?) -> GatewayAdmissionSetting)? = null) {
    var inspection by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var controlGateway by remember { mutableStateOf<String?>(null) }
    controlGateway?.let { uid -> GatewayControlSettingsDialog(onDismiss = { controlGateway = null }, initialProfileUid = uid) }
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
        if (state.privacyModeLocked == true) {
            Text("Private chat · Repository map requests and external sharing require a verified private route.", style = MaterialTheme.typography.bodySmall)
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
                        onSettings(state.profileSettings.copy(engineeringGatewayProfileUid = null, engineeringGatewayDefaultApplied = true))
                    })
                    state.profiles.filter { dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy.supportsSavedJobs(it) }.forEach { gateway ->
                        DropdownMenuItem(text = { Text(gateway.name) }, onClick = {
                            gatewayMenu = false
                            onSettings(state.profileSettings.copy(engineeringGatewayProfileUid = gateway.uid, engineeringGatewayDefaultApplied = true))
                        })
                    }
                }
                Text("Add a separate Llama or Ollama connection in Platforms using the desktop gateway address, then select it here.", style = MaterialTheme.typography.bodySmall)
                Text("Gateway execution mode", style = MaterialTheme.typography.titleSmall)
                val gateway = dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile(state.profiles, state.profileSettings.engineeringGatewayProfileUid)
                val inference = state.profiles.singleOrNull { it.enabled && it.uid == state.profileUid }
                val account = workspace.connection?.connectionUid
                val repository = workspace.selection?.fullName
                if (inference != null && gateway != null && account != null && repository != null && !dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy.supportsSavedJobs(inference)) {
                    val consentKey = dev.chungjungsoo.gptmobile.data.github.GatewayGraphSharing.key(inference, account, repository, gateway.uid)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Share repository map results from $repository with ${inference.model}", Modifier.weight(1f))
                        Switch(
                            checked = state.profileSettings.graphSharingConsent == consentKey,
                            enabled = state.privacyModeLocked == false,
                            onCheckedChange = { enabled -> onSettings(state.profileSettings.copy(graphSharingConsent = consentKey.takeIf { enabled })) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Run approved Gateway checks and share the output with ${inference.model}", Modifier.weight(1f))
                        Switch(
                            checked = state.profileSettings.workspaceCheckConsent == consentKey,
                            enabled = state.privacyModeLocked == false,
                            onCheckedChange = { enabled -> onSettings(state.profileSettings.copy(workspaceCheckConsent = consentKey.takeIf { enabled })) }
                        )
                    }
                }
                val androidContext = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.TextButton(onClick = { androidContext.startActivity(android.content.Intent(androidContext, OwnerPairingActivity::class.java)) }) { Text("Pair a Windows Gateway") }
                OutlinedButton(onClick = { controlGateway = gateway?.uid }, enabled = gateway != null, modifier = Modifier.testTag("github_gateway_controls")) { Text("Engineering & Gateway settings") }
                Text("Shares the confirmed PC settings with Settings › Gateway. Pair this phone and confirm the target before enabling owner Engineering.", style = MaterialTheme.typography.bodySmall)
                var batchModeOpen by remember(state.profileUid, gateway) { mutableStateOf(false) }
                var availableModes by remember(batchModeOpen, state.profileUid, gateway) { mutableStateOf<Set<String>?>(null) }
                androidx.compose.runtime.LaunchedEffect(batchModeOpen, state.profileUid, gateway) {
                    val profile = state.profileUid
                    if (batchModeOpen && profile != null && gateway != null) {
                        availableModes = try {
                            onBatchModes(profile, gateway.uid)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptySet()
                        }
                    }
                }
                OutlinedButton(
                    onClick = { batchModeOpen = true },
                    enabled = state.profileUid != null && gateway != null,
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
                    if (availableModes == null || availableModes?.isEmpty() == true) {
                        Text(if (availableModes == null) "Checking Gateway modes…" else "Couldn’t verify Gateway modes. Reopen to retry.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    listOf("standard" to "Standard", "smart" to "Smart Batch", "full" to "Full Integration Batch").forEach { (mode, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            enabled = mode == "standard" || availableModes?.contains(mode) == true,
                            onClick = {
                                batchModeOpen = false
                                onSettings(state.profileSettings.copy(executionBatchMode = mode))
                            }
                        )
                    }
                }
                Text(
                    "Smart and Full Batch availability follows the selected Gateway. Your choice applies to new jobs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GitHubGatewayConnectionCheck(
                    dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile(state.profiles, state.profileSettings.engineeringGatewayProfileUid)?.uid
                ) { inspection = it to true }
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub Copilot · Cloud work", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Run cloud tasks, request reviews, and let Agent Merge help with failed CI or conflicts. Works with your selected local or remote AI profile.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow Copilot for this chat", Modifier.weight(1f))
                    Switch(
                        checked = state.copilotEnabled,
                        onCheckedChange = onCopilotEnabled,
                        enabled = state.copilotPluginEnabled && state.chatId?.let { it > 0 } == true,
                        modifier = Modifier.testTag("github_chat_copilot_toggle")
                    )
                }
                Text(
                    if (!state.copilotPluginEnabled) {
                        "Off. Enable Copilot first in GitHub plugin settings."
                    } else if (state.chatId == null) {
                        "Start or open a GitHub conversation to configure its consent."
                    } else {
                        "Off for new chats. Turning this off blocks new Copilot calls; accepted cloud tasks continue on GitHub. Use agent_merge_disable to stop a watched PR. Automatic merging stays off."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Cloud task controller", style = MaterialTheme.typography.titleSmall)
                var copilotOwnerMenu by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { copilotOwnerMenu = true },
                    enabled = state.profileUid != null,
                    modifier = Modifier.testTag("github_copilot_execution_owner")
                ) {
                    Text(if (state.profileSettings.copilotExecutionOwner == GitHubCopilotExecutionOwner.GATEWAY) "Standalone Gateway" else "Direct from this app")
                }
                DropdownMenu(copilotOwnerMenu, { copilotOwnerMenu = false }) {
                    GitHubCopilotExecutionOwner.entries.forEach { owner ->
                        DropdownMenuItem(
                            text = { Text(if (owner == GitHubCopilotExecutionOwner.GATEWAY) "Standalone Gateway" else "Direct from this app") },
                            onClick = {
                                copilotOwnerMenu = false
                                onSettings(state.profileSettings.copy(copilotExecutionOwner = owner))
                            }
                        )
                    }
                }
                Text(
                    if (state.profileSettings.copilotExecutionOwner == GitHubCopilotExecutionOwner.GATEWAY) {
                        "The selected engineering gateway saves and monitors cloud tasks while this app is closed. Enable Copilot in the Gateway's GitHub settings and connect its own GitHub account. An unavailable Gateway pauses this route."
                    } else {
                        "No Gateway is required. The app submits directly to GitHub and saves the task ID. GitHub continues accepted tasks while the app is closed; reopen this chat to check progress."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "This choice applies to native cloud tasks. Existing tasks retain their original controller and account. The task API preview requires Copilot Business or Enterprise and Agent tasks permission; separately configured GitHub MCP cloud tools support paid Copilot plans. Agent Merge and review permissions remain separate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                Text("Cache source files", style = MaterialTheme.typography.titleSmall)
                Text("Cache file contents for on-demand reads and progressive Repository map analysis on this phone. Gateway is optional.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(state.prepareIndex, onIndex, modifier = Modifier.testTag("github_index_option"))
        }
        workspace.indexStatus?.let { status ->
            val indexed = status["indexed_blobs"]?.jsonPrimitive?.intOrNull ?: 0
            val total = status["total_paths"]?.jsonPrimitive?.intOrNull
            Text(if (total == null) "$indexed files cached. Coverage is checked against the selected revision." else "$indexed of $total files cached. Partial indexing remains visible.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.prepareIndex) OutlinedButton(onClick = onPrepare, enabled = !workspace.busy) { Text("Cache next source batch") }
    }
}

@Composable
private fun GitHubDecisionSettings(mode: GitHubDecisionMode, enabled: Boolean, onChange: (GitHubDecisionMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun label(value: GitHubDecisionMode) = when (value) {
        GitHubDecisionMode.ASK -> "Ask each time"
        GitHubDecisionMode.SUGGESTED -> "Use suggested choices"
        GitHubDecisionMode.AUTO_APPROVE -> "Fixture-only auto-approval"
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
                    GitHubDecisionMode.AUTO_APPROVE -> "Use suggested choices and auto-approve only verified patches on owned codex-self-test/* fixture branches. Commits to regular repository branches still require the native Approve & Commit action."
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
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
            if (advanced) {
                GitHubOverrideSwitch("Maximum context expansion", "Expand up to the provider or local model capacity, with room for results and review.", value.maximumContext, enabled) { onChange(value.copy(maximumContext = it)) }
                Text("Engineering memory", style = MaterialTheme.typography.titleSmall)
                if (value.engineeringMemory != dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryMode.SESSION_ONLY) {
                    Text("To record a repair result, send “feedback: worked” or “feedback: failed” followed by its evidence ID. To link a screenshot, attach it and send “evidence:” followed by the ID.", style = MaterialTheme.typography.bodySmall)
                }
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
            }
            Column {
                Text("Engineering budget", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("github_engineering_budget")) { Text(engineeringLabel(value.engineeringMode)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(dev.chungjungsoo.gptmobile.data.context.EngineeringMode.OFF, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.ULTRA_MAX, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.ENGINEERING_MAX, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.DEEP, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.STANDARD, dev.chungjungsoo.gptmobile.data.context.EngineeringMode.QUICK).forEach { mode ->
                        DropdownMenuItem(text = { Text(engineeringLabel(mode)) }, onClick = {
                            menu = false
                            onChange(value.copy(engineeringMode = mode))
                        })
                    }
                }
            }
            val modeDescription = when (value.engineeringMode) {
                dev.chungjungsoo.gptmobile.data.context.EngineeringMode.QUICK -> "Fast uses up to 8,192 context tokens and 2,048 output tokens for a focused investigation."
                dev.chungjungsoo.gptmobile.data.context.EngineeringMode.STANDARD -> "Balanced uses up to 16,384 context tokens and 4,096 output tokens for normal implementation and validation."
                dev.chungjungsoo.gptmobile.data.context.EngineeringMode.DEEP -> "Thorough uses up to 32,768 context tokens and 8,192 output tokens for more source and a longer analysis."
                else -> null
            }
            if (modeDescription != null) Text("$modeDescription Profile and model limits also apply.", style = MaterialTheme.typography.bodySmall)
            Text("Ultra Maximum is the default. GitHub work allows up to 512 tool calls; saved gateway jobs request up to 24 hours and 512 model calls. Your saved limits still apply, and each job shows the allowance accepted by its gateway.", style = MaterialTheme.typography.bodySmall)
            Text("Llama gateway · Review required before the final answer. Design decisions appear as buttons, with a custom answer option.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun engineeringLabel(mode: dev.chungjungsoo.gptmobile.data.context.EngineeringMode): String = when (mode) {
    dev.chungjungsoo.gptmobile.data.context.EngineeringMode.ULTRA_MAX -> "Ultra Maximum"
    dev.chungjungsoo.gptmobile.data.context.EngineeringMode.QUICK -> "Fast"
    dev.chungjungsoo.gptmobile.data.context.EngineeringMode.STANDARD -> "Balanced"
    dev.chungjungsoo.gptmobile.data.context.EngineeringMode.DEEP -> "Thorough"
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

@Composable
private fun GatewayAdmissionControl(loadOrToggle: suspend (GatewayAdmissionSetting?) -> GatewayAdmissionSetting) {
    val scope = rememberCoroutineScope()
    var observed by remember { mutableStateOf<GatewayAdmissionSetting?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun request(previous: GatewayAdmissionSetting?) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                observed = loadOrToggle(previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                observed = null
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val current = observed
    if (current != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow new saved engineering jobs")
                Text(
                    if (current.principalId == "network:shared") "Read-only on a shared network. Change admission in Windows Gateway Settings." else "Applies to your account on this Gateway.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = current.enabled, enabled = !busy && current.principalId != "network:shared", onCheckedChange = { request(current) })
        }
    } else {
        OutlinedButton(onClick = { request(null) }, enabled = !busy) {
            Text(if (busy) "Reading Gateway setting…" else "Check saved-job setting")
        }
        if (failed) {
            Text("Current setting unknown. Check the connection, host version and engineering access, then read it again.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
