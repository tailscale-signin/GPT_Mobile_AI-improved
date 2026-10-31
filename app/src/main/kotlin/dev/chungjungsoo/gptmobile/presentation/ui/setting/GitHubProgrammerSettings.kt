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
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun GitHubProfilePicker(profiles: List<PlatformV2>, selectedUid: String?, onSelect: (String?) -> Unit, global: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(if (global) "Default GitHub AI profile" else "Engineering AI profile", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("github_main_profile")) {
            Text(profiles.firstOrNull { it.uid == selectedUid }?.name?.take(120) ?: if (selectedUid == null) if (global) "Use favorite AI profile" else "Use global default" else "Selected profile unavailable", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(if (global) "Use favorite AI profile" else "Use global default") }, onClick = {
                expanded = false
                onSelect(null)
            })
            profiles.filter { it.enabled }.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name.take(120), maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                    expanded = false
                    onSelect(profile.uid)
                })
            }
        }
    }
}

@Composable
internal fun GitHubProgrammerSettings(workspace: GitHubWorkspaceState, state: GitHubProgrammerState, onProfile: (String?) -> Unit, onIndex: (Boolean) -> Unit, onPrepare: () -> Unit, onSettings: (dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitHubProfilePicker(state.profiles, state.profileOverrideUid, onProfile)
        GitHubProfileOverrides(state.profileSettings, state.profileUid != null, onSettings)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("GitHub tools run automatically in this workspace. Each commit asks once, with a branch and change summary.", style = MaterialTheme.typography.bodySmall)
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
private fun GitHubProfileOverrides(value: dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride, enabled: Boolean, onChange: (dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GitHub chat preferences", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Saved separately for this AI profile in GitHub chats.", style = MaterialTheme.typography.bodySmall)
            Text("Verified Engineering is on: exact receipt facts, explicit unknowns, fresh state checks, and private Llama review. Open Evidence in the chat to inspect retained receipts.", style = MaterialTheme.typography.bodySmall)
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
            Text("Ultra Maximum supports up to 512 tool calls and 24 hours per job. Provider limits and usage charges still apply.", style = MaterialTheme.typography.bodySmall)
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
