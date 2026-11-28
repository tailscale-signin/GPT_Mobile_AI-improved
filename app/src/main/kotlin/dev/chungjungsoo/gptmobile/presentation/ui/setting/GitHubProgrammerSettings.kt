package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSetting
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.github.GitHubCopilotExecutionOwner
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionMode
import dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel
import dev.chungjungsoo.gptmobile.data.github.GitHubNextActionMode
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
            Text(selected?.name?.take(120) ?: if (selectedUid == null) "Choose an AI profile" else "Selected profile unavailable", maxLines = 2, overflow = TextOverflow.Ellipsis)
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
internal fun GitHubProgrammerSettings(workspace: GitHubWorkspaceState, state: GitHubProgrammerState, onProfile: (String?) -> Unit, onIndex: (Boolean) -> Unit, onPrepare: () -> Unit, onSettings: (GitHubChatProfileOverride) -> Unit = {}, onExport: () -> Unit = {}, onOpenRepositoryMap: (() -> Unit)? = null, onDecisionMode: (GitHubDecisionMode) -> Unit = {}, onCopilotEnabled: (Boolean) -> Unit = {}, onBatchModes: suspend (String, String) -> Set<String> = { _, _ -> emptySet() }, onAdmissionSetting: (suspend (String, String, GatewayAdmissionSetting?) -> GatewayAdmissionSetting)? = null, onOpenInventory: (() -> Unit)? = null) {
    var inspection by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var controlGateway by remember { mutableStateOf<String?>(null) }
    controlGateway?.let { uid -> GatewayControlSettingsDialog(onDismiss = { controlGateway = null }, initialProfileUid = uid) }
    inspection?.let { (profileUid, engineering) ->
        ConnectionDoctorDialog(onDismiss = { inspection = null }, initialProfileUid = profileUid, engineeringGateway = engineering)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitHubProfilePicker(state.profiles, state.selectedProfileUid ?: state.profileUid ?: state.profileOverrideUid, onProfile)
        OutlinedButton(onClick = { state.profileUid?.let { inspection = it to false } }, enabled = state.profileUid != null, modifier = Modifier.testTag("github_test_connection")) { Text("Test AI connection") }
        onOpenInventory?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().testTag("github_tools_features")) { Text("Tools and features") }
        }
        onOpenRepositoryMap?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().testTag("github_repository_map")) { Text("Repository map") }
        }
        if (state.privacyModeLocked == true) {
            Text("Private chat · Repository map requests and external sharing require a verified private route.", style = MaterialTheme.typography.bodySmall)
        }
        GitHubProfileOverrides(state.profileSettings, state.profileUid != null, state.profiles.firstOrNull { it.uid == state.profileUid }?.maxToolCalls, onSettings)
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
        GitHubSettingsSection("Quick actions", "${state.profileSettings.quickWorkflows.size} shortcuts in the message box") {
            GitHubWorkflowSettings(state.profileSettings.quickWorkflows, state.profileUid != null) {
                onSettings(state.profileSettings.copy(quickWorkflows = it))
            }
        }
        GitHubSettingsSection("Model tuning and review", state.profileSettings.sampling.mode.name.lowercase().replaceFirstChar { it.uppercase() } + " creativity") {
            GitHubCreativitySettings(state.profileSettings.sampling, state.profileUid != null) {
                onSettings(state.profileSettings.copy(sampling = it))
            }
            GitHubRecoveryConsultSetting(state, onSettings)
            if (state.profileSettings.intelligenceLevel in dev.chungjungsoo.gptmobile.data.github.GitHubPrimaryPatchReview.levels) {
                val key = state.patchReviewerKey
                GitHubOverrideSwitch(
                    "Independent patch review",
                    state.patchReviewerName?.let { "Send up to three staged file excerpts to $it once per task." }
                        ?: "Select a different named reviewer in Model Delegation.",
                    key != null && key == state.profileSettings.primaryPatchReviewConsent,
                    key != null && state.privacyModeLocked == false
                ) { enabled -> onSettings(state.profileSettings.copy(primaryPatchReviewConsent = key.takeIf { enabled })) }
            }
            if (state.profileSettings.intelligenceLevel == GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE) {
                val key = state.engineeringTeamKey
                GitHubOverrideSwitch(
                    "Engineering team",
                    state.engineeringTeamDescription?.let { "$it Share selected repository excerpts for analysis and code review in this workspace." }
                        ?: "Configure eligible helpers and review preferences in Model Delegation.",
                    key != null && key == state.profileSettings.engineeringTeamConsent,
                    key != null && state.privacyModeLocked == false
                ) { enabled -> onSettings(state.profileSettings.copy(engineeringTeamConsent = key.takeIf { enabled })) }
            }
        }
        val gatewayUid = state.profileSettings.engineeringGatewayProfileUid
        val gateway = dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile(state.profiles, gatewayUid)
        val gatewayLabel = when {
            gatewayUid == null -> "Automatic gateway"
            gateway != null -> gateway.name
            else -> "Selected Gateway unavailable"
        }
        GitHubSettingsSection("Desktop Gateway", if (gatewayUid == null) "Automatic for connected gateways" else gatewayLabel) {
            var gatewayMenu by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { gatewayMenu = true }, enabled = state.profileUid != null, modifier = Modifier.testTag("github_gateway_selection")) {
                Text(gatewayLabel)
            }
            DropdownMenu(gatewayMenu, { gatewayMenu = false }) {
                DropdownMenuItem(text = { Text("Automatic gateway") }, onClick = {
                    gatewayMenu = false
                    onSettings(state.profileSettings.copy(engineeringGatewayProfileUid = null, engineeringGatewayDefaultApplied = false))
                })
                state.profiles.filter { dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy.supportsSavedJobs(it) }.forEach { gateway ->
                    DropdownMenuItem(text = { Text(gateway.name) }, onClick = {
                        gatewayMenu = false
                        onSettings(state.profileSettings.copy(engineeringGatewayProfileUid = gateway.uid, engineeringGatewayDefaultApplied = true))
                    })
                }
            }
            Text(
                when {
                    gatewayUid == null -> "GitHub uses the selected profile’s verified local gateway automatically. Choose a different connection here if needed."
                    gateway == null -> "The saved Gateway is disabled or unavailable. Choose a connection or Automatic gateway to continue."
                    else -> "Gateway jobs use ${gateway.name}. Choose a different connection here if needed."
                },
                style = MaterialTheme.typography.bodySmall
            )
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
            Text("Engineering is on by default for a verified owner connection. Gateway settings show job readiness and owner controls.", style = MaterialTheme.typography.bodySmall)
            Text("Job batching", style = MaterialTheme.typography.titleSmall)
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
            GitHubGatewayConnectionCheck(gateway?.uid) { inspection = it to true }
            if (inference != null && gateway != null && onAdmissionSetting != null) {
                key(inference.uid, gateway.uid) {
                    GatewayAdmissionControl { previous -> onAdmissionSetting(inference.uid, gateway.uid, previous) }
                }
            }
        }
        GitHubSettingsSection(
            "GitHub Copilot",
            when {
                state.copilotEnabled -> "Enabled for this conversation"
                state.copilotPluginEnabled && state.chatId == null -> "On by default for GitHub chats"
                else -> "Cloud tasks and reviews · Off"
            }
        ) {
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
                    "Enable Copilot in GitHub plugin settings to use it here."
                } else if (state.chatId == null) {
                    "Copilot is on by default when you start a saved GitHub conversation. Its account and repository permissions are checked before any work starts."
                } else {
                    "Progress shows what is being delegated to Copilot and the state GitHub reports. Turning this off blocks new Copilot work. Accepted cloud tasks continue on GitHub."
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
                    "The selected engineering gateway saves and monitors cloud tasks while this app is closed. Copilot is on by default in the Gateway; connect its own eligible GitHub account. An unavailable Gateway pauses this route."
                } else {
                    "No Gateway is required. The app submits directly to GitHub and saves the task ID. GitHub continues accepted tasks while the app is closed; reopen this chat to check progress."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Existing tasks keep their original controller. Cloud tasks require an eligible Copilot plan and GitHub permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onExport, enabled = state.chatId?.let { it > 0 } == true, modifier = Modifier.fillMaxWidth().testTag("github_export_conversation")) {
            Text("Export conversation")
        }
        Text("Save this GitHub conversation as Markdown or plain text. Debug mode also offers a diagnostic ZIP.", style = MaterialTheme.typography.bodySmall)
        state.chatId?.takeIf { it > 0 }?.let { dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubEvidenceButton(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
private fun GitHubRecoveryConsultSetting(state: GitHubProgrammerState, onChange: (GitHubChatProfileOverride) -> Unit) {
    val adviceConsent = state.profileSettings.recoveryConsultConsent
    val executionConsent = state.profileSettings.recoveryExecutionConsent
    val consent = executionConsent ?: adviceConsent
    if (state.profileSettings.intelligenceLevel != GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE && consent == null) return
    val executionUnavailableReason = state.profiles.singleOrNull { it.uid == state.profileUid }
        ?.let(dev.chungjungsoo.gptmobile.data.github.GitHubRecoveryConsult::executionUnavailableReason)
    val candidates = if (state.privacyModeLocked == false) state.recoveryAdvisors else emptyMap()
    val executionProfiles = if (state.privacyModeLocked == false && executionUnavailableReason == null) state.recoveryExecutionProfiles else emptyMap()
    val selected = dev.chungjungsoo.gptmobile.data.github.GitHubRecoveryConsult.selectedProfile(state.profileSettings, candidates, executionProfiles)
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Recovery profile", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { expanded = true }, enabled = candidates.isNotEmpty() || executionProfiles.isNotEmpty() || consent != null, modifier = Modifier.testTag("github_recovery_advisor")) {
            Text(selected?.let { "${it.name} · " + if (executionConsent != null) "Recovery read" else "Advice" } ?: if (consent != null) "Saved profile unavailable" else "Selected profile only")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Selected profile only") }, onClick = {
                expanded = false
                onChange(state.profileSettings.copy(recoveryConsultConsent = null, recoveryExecutionConsent = null))
            })
            executionProfiles.forEach { (key, helper) ->
                val destination = if (helper.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM) "on device" else "provider charges may apply"
                DropdownMenuItem(text = { Text("Recovery read · ${helper.name} · ${helper.model} ($destination)") }, onClick = {
                    expanded = false
                    onChange(state.profileSettings.copy(recoveryConsultConsent = null, recoveryExecutionConsent = key))
                })
            }
            candidates.forEach { (key, helper) ->
                DropdownMenuItem(text = { Text("Advice only · ${helper.name} · ${helper.model}") }, onClick = {
                    expanded = false
                    onChange(state.profileSettings.copy(recoveryConsultConsent = key, recoveryExecutionConsent = null))
                })
            }
        }
        val guidance = when {
            state.privacyModeLocked == true -> "Private chats use the selected profile for recovery."
            executionUnavailableReason != null -> executionUnavailableReason
            executionProfiles.isNotEmpty() -> "Recovery read sends the request and unfinished criterion to your chosen model. It can select one pinned search or source read. The primary receives the evidence and owns all writes. One invocation per task, up to 256 output tokens within your spend limit."
            candidates.isEmpty() -> "Open a saved GitHub chat, enable delegation and automatic fallback, and approve a second profile. Network profiles also require remote worker permission."
            else -> "For this repository and branch, the on-device advisor can suggest a next step after an empty answer or unfinished criterion. It receives the request and sends a text proposal to the primary profile."
        }
        Text(guidance, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GitHubProfileOverrides(value: GitHubChatProfileOverride, enabled: Boolean, configuredCallLimit: Int?, onChange: (GitHubChatProfileOverride) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var intelligenceMenu by remember { mutableStateOf(false) }
    var nextActionMenu by remember { mutableStateOf(false) }
    var workflowMenu by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GitHub chat preferences", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Saved for this AI profile · ${engineeringLabel(value.engineeringMode)} budget", style = MaterialTheme.typography.bodySmall)
            Column {
                Text("Intelligence level", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { intelligenceMenu = true }, enabled = enabled, modifier = Modifier.testTag("github_intelligence_level")) {
                    Text(value.intelligenceLevel.title)
                }
                DropdownMenu(expanded = intelligenceMenu, onDismissRequest = { intelligenceMenu = false }) {
                    GitHubIntelligenceLevel.entries.forEach { level ->
                        DropdownMenuItem(text = { Text(level.title) }, onClick = {
                            intelligenceMenu = false
                            onChange(value.copy(intelligenceLevel = level))
                        })
                    }
                }
                Text(value.intelligenceLevel.description, style = MaterialTheme.typography.bodySmall)
            }
            GitHubOverrideSwitch("Web search", "Search the web and read source pages.", value.webSearch, enabled) { onChange(value.copy(webSearch = it)) }
            GitHubOverrideSwitch("GitHub tools", "Use the connected repository and review changes before committing.", value.githubTools, enabled) { onChange(value.copy(githubTools = it)) }
            Column {
                Text("Repository context", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { contextMenu = true }, enabled = enabled && value.githubTools, modifier = Modifier.testTag("github_context_variant")) {
                    Text(repositoryContextLabel(value.contextVariant))
                }
                DropdownMenu(expanded = contextMenu, onDismissRequest = { contextMenu = false }) {
                    dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant.entries.forEach { variant ->
                        DropdownMenuItem(text = { Text(repositoryContextLabel(variant)) }, onClick = {
                            contextMenu = false
                            onChange(value.copy(contextVariant = variant))
                        })
                    }
                }
                Text("Task context selects source ranges, repository guidance and test candidates for your request. Semantic context adds local similarity ranking. All choices keep the selected model, permissions and source revision.", style = MaterialTheme.typography.bodySmall)
            }
            GitHubOverrideSwitch("Evidence-based progress summaries", "Show what changed and why it matters as verified findings arrive.", value.evidenceElaboration, enabled) { onChange(value.copy(evidenceElaboration = it)) }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
            if (advanced) {
                Column {
                    Text("Next action selection", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { nextActionMenu = true }, enabled = enabled, modifier = Modifier.testTag("github_next_action_policy")) {
                        Text(value.nextActionPolicy.title)
                    }
                    DropdownMenu(expanded = nextActionMenu, onDismissRequest = { nextActionMenu = false }) {
                        GitHubNextActionMode.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.title) }, onClick = {
                                nextActionMenu = false
                                onChange(value.copy(nextActionPolicy = mode))
                            })
                        }
                    }
                    Text(value.nextActionPolicy.description, style = MaterialTheme.typography.bodySmall)
                }
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
                    val effective = configuredCallLimit?.let { dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLimits.callLimit(preset.prompt(), minOf(it, dev.chungjungsoo.gptmobile.data.github.GitHubChatPolicy.callLimit(value.engineeringMode)), preset) }
                    Text(
                        if (effective == null) "Select a profile to see its allowance." else "Up to ${effective.effective} tool calls. The request and conversation can set a lower limit.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column {
                    Text("Engineering budget", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("github_engineering_budget")) { Text(engineeringLabel(value.engineeringMode)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(EngineeringMode.ADAPTIVE, EngineeringMode.QUICK, EngineeringMode.STANDARD, EngineeringMode.DEEP, EngineeringMode.ENGINEERING_MAX, EngineeringMode.ULTRA_MAX, EngineeringMode.OFF).forEach { mode ->
                            DropdownMenuItem(text = { Text(engineeringLabel(mode)) }, onClick = {
                                menu = false
                                onChange(value.copy(engineeringMode = mode))
                            })
                        }
                    }
                }
                val modeDescription = when (value.engineeringMode) {
                    EngineeringMode.QUICK -> "Fast uses up to 8,192 context tokens and 2,048 output tokens for a focused investigation."
                    EngineeringMode.STANDARD -> "Balanced uses up to 16,384 context tokens and 4,096 output tokens for normal implementation and validation."
                    EngineeringMode.DEEP -> "Thorough uses up to 32,768 context tokens and 8,192 output tokens for more source and a longer analysis."
                    EngineeringMode.ADAPTIVE -> "Automatic adjusts the context and response budget to the task."
                    else -> null
                }
                if (modeDescription != null) Text("$modeDescription Profile and model limits also apply.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GitHubSettingsSection(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (expanded) content()
        }
    }
}

private fun engineeringLabel(mode: EngineeringMode): String = when (mode) {
    EngineeringMode.ADAPTIVE -> "Automatic"
    EngineeringMode.ULTRA_MAX -> "Ultra Maximum"
    EngineeringMode.QUICK -> "Fast"
    EngineeringMode.STANDARD -> "Balanced"
    EngineeringMode.DEEP -> "Thorough"
    EngineeringMode.OFF -> "Use AI profile limits"
    else -> mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun GitHubOverrideSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun GatewayAdmissionControl(loadReadiness: suspend (GatewayAdmissionSetting?) -> GatewayAdmissionSetting) {
    val scope = rememberCoroutineScope()
    var observed by remember { mutableStateOf<GatewayAdmissionSetting?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun request() {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                observed = loadReadiness(null)
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
        Text(if (current.enabled && current.principalId != "network:shared") "Ready for engineering jobs" else "Gateway setup needed")
        Text(
            when {
                current.principalId == "network:shared" -> "Connect this phone to the PC’s owner account in Gateway settings."
                !current.enabled -> "Update Gateway or complete setup on the PC, then refresh readiness."
                else -> "Engineering runs automatically for this account. You can stop individual jobs from their task controls."
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
    OutlinedButton(onClick = { request() }, enabled = !busy) {
        Text(
            if (busy) {
                "Checking readiness…"
            } else if (current == null) {
                "Check engineering readiness"
            } else {
                "Refresh readiness"
            }
        )
    }
    if (failed) {
        Text("Readiness is unknown. Check the connection, host version and engineering access, then try again.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun repositoryContextLabel(variant: dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant): String = when (variant) {
    dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant.CURRENT -> "Current context baseline"
    dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant.REFERENCE -> "Repository reference"
    dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant.TASK -> "Task context (recommended)"
    dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant.SEMANTIC -> "Task context with local similarity"
}
