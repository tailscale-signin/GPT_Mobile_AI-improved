package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun GitHubProgrammerState.activityLabel(): String = when {
    submitting -> "Saving command"
    approvals.isNotEmpty() -> "Needs your approval"
    runs.any { it.status in setOf("RUNNING", "QUEUED", "PENDING") } -> "Working"
    pending.isNotEmpty() -> if (pending.any { it.paused }) "Paused" else "Command queued"
    runs.any { it.status.startsWith("WAITING_") } -> "Waiting"
    runs.lastOrNull()?.status in setOf("FAILED", "INTERRUPTED") -> "Needs attention"
    runs.lastOrNull()?.status in setOf("CANCELED", "CANCELLED") -> "Stopped"
    runs.lastOrNull()?.status == "COMPLETED" -> "Answer ready"
    else -> "Ready for a command"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GitHubProgrammerHome(
    workspace: GitHubWorkspaceState,
    state: GitHubProgrammerState,
    onSelectProfile: (String) -> Unit,
    onCommand: (String) -> Unit,
    onOpenConversation: (Int) -> Unit,
    onOpenChanges: () -> Unit,
    onOpenChecks: () -> Unit,
    onOpenFiles: () -> Unit,
    onPrepareIndexChange: (Boolean) -> Unit,
    onPrepareIndex: () -> Unit,
    onDismissError: () -> Unit
) {
    val profile = state.profiles.firstOrNull { it.uid == state.profileUid }
    var profileMenu by remember { mutableStateOf(false) }
    val running = state.activityLabel() in setOf("Working", "Waiting", "Command queued", "Saving command")
    Column(Modifier.fillMaxWidth().testTag("github_programmer_home"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(26.dp))
                    Text("Your AI programmer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Text("Describe the outcome. Review the changes. Follow the result.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { profileMenu = true }, modifier = Modifier.weight(1f).testTag("github_main_profile")) {
                        Icon(Icons.Rounded.SmartToy, null, Modifier.size(18.dp))
                        Text(profile?.name?.take(120) ?: "Select main AI profile", Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        state.profiles.forEach { item ->
                            DropdownMenuItem(text = {
                                Column {
                                    Text(item.name.take(120), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.model.take(120), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }, leadingIcon = { Icon(Icons.Rounded.SmartToy, null) }, onClick = {
                                profileMenu = false
                                onSelectProfile(item.uid)
                            })
                        }
                        if (state.profiles.isEmpty()) DropdownMenuItem(text = { Text("Add an AI profile in Settings") }, enabled = false, onClick = {})
                    }
                }
                Text(state.activityLabel(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("github_programmer_status"))
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProgrammerAction("Fix CI", Icons.Rounded.Build) { onCommand("Investigate the failing GitHub checks, propose the smallest safe fix, and prepare reviewed changes.") }
            ProgrammerAction("Review PRs", Icons.Rounded.FactCheck) { onCommand("Review the open pull requests. Explain the risks and required changes, and report the current checks.") }
            ProgrammerAction("Plan a change", Icons.Rounded.Code) { onCommand("Inspect the repository and propose an implementation plan for: ") }
            ProgrammerAction("Summarize work", Icons.Rounded.CheckCircle) { onCommand("Summarize the verified commits, pull requests, pending approvals and remaining checks in this repository.") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgrammerMetric("Tool results", state.events.count { it.status == ToolEventStatus.COMPLETED && !it.isError }.toString(), Icons.Rounded.CheckCircle, Modifier.weight(1f))
            ProgrammerMetric("Approvals", state.approvals.size.toString(), Icons.Rounded.FactCheck, Modifier.weight(1f))
            ProgrammerMetric("Queued", state.pending.size.toString(), Icons.Rounded.PlayArrow, Modifier.weight(1f))
        }

        state.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }

        val answer = state.messages.lastOrNull { it.platformType != null && it.content.isNotBlank() }
        if (answer != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Latest AI update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                    Text(answer.content.take(2400), Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                state.chatId?.let { id ->
                    OutlinedButton(onClick = { onOpenConversation(id) }) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(18.dp))
                        Text("Open full conversation", Modifier.padding(start = 8.dp))
                    }
                }
            }
        } else {
            Text("Ask for a fix, a review or a new feature. Your main AI profile will work in this repository, with approval before consequential writes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.events.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent operations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.events.takeLast(5).asReversed().forEach { event ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (event.isError) Icons.Rounded.Build else Icons.Rounded.Code, null, Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(event.toolName.substringBefore("__").removePrefix("github_").replace('_', ' '), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(event.status.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = if (event.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenChanges) {
                Icon(Icons.Rounded.AccountTree, null, Modifier.size(18.dp))
                Text("Review changes", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onOpenChecks) {
                Icon(Icons.Rounded.FactCheck, null, Modifier.size(18.dp))
                Text("Checks & builds", Modifier.padding(start = 8.dp))
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Memory, null, Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Prepare code context", style = MaterialTheme.typography.titleSmall)
                        Text("Optional indexing for faster AI retrieval", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.prepareIndex, onCheckedChange = onPrepareIndexChange, modifier = Modifier.testTag("github_index_option"))
                }
                val cached = workspace.indexStatus?.get("indexed_blobs")?.jsonPrimitive?.intOrNull ?: 0
                val total = workspace.indexStatus?.get("total_paths")?.jsonPrimitive?.intOrNull
                Text(if (total == null) "Files are retrieved when needed." else "$cached of $total files cached. Repository changes are checked against the branch head.", style = MaterialTheme.typography.bodySmall)
                if (state.prepareIndex) TextButton(onClick = onPrepareIndex, enabled = !workspace.busy) { Text("Prepare next batch") }
                TextButton(onClick = onOpenFiles) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                    Text("Browse repository tools", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ProgrammerAction(label: String, icon: ImageVector, action: () -> Unit) {
    AssistChip(onClick = action, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
}

@Composable
private fun ProgrammerMetric(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
