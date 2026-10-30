package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatMarkdown

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
    onCommand: (String) -> Unit,
    onOpenChanges: () -> Unit,
    onOpenChecks: () -> Unit,
    onOpenSettings: () -> Unit,
    onLoadEarlier: () -> Unit,
    onDismissError: () -> Unit
) {
    val scroll = rememberLazyListState()
    val messages = state.messages.filter { it.effectiveContent().isNotBlank() }
    val running = state.activityLabel() in setOf("Working", "Waiting", "Command queued", "Saving command")
    var operationsExpanded by remember { mutableStateOf(false) }
    // Follow new messages only while the reader is already at the bottom.
    val following = !scroll.canScrollForward
    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.content?.length) {
        if (following && scroll.layoutInfo.totalItemsCount > 0) scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalAnswerPane provides dev.chungjungsoo.gptmobile.presentation.ui.chat.AnswerPane(maxWidth.value, maxHeight.value)) {
            LazyColumn(Modifier.fillMaxSize().testTag("github_programmer_home"), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.profiles.firstOrNull { it.uid == state.profileUid }?.name ?: "Select an AI profile in Settings", style = MaterialTheme.typography.labelMedium)
                        Text(state.activityLabel(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("github_programmer_status"))
                        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (workspace.selection == null || state.profileUid == null) {
                            OutlinedButton(onClick = onOpenSettings) {
                                Icon(Icons.Rounded.Settings, null)
                                Text("Set up repository and AI profile")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { onCommand("Audit this branch for bugs, security risks and missing tests. Report verified findings and propose focused fixes.") }, label = { Text("Audit branch") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                            AssistChip(onClick = { onCommand("Investigate the failing GitHub checks, propose the smallest safe fix, and prepare reviewed changes.") }, label = { Text("Fix CI") }, leadingIcon = { Icon(Icons.Rounded.Check, null) })
                            AssistChip(onClick = { onCommand("Inspect the current branch changes and prepare a draft pull request after validation and my approval.") }, label = { Text("Prepare PR") }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null) })
                            AssistChip(onClick = onOpenChecks, label = { Text("Check CI") }, leadingIcon = { Icon(Icons.Rounded.Check, null) })
                        }
                    }
                }
                listOfNotNull(workspace.error, state.error).distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")?.let { error ->
                    item(key = "error") {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(error)
                                OutlinedButton(onClick = onDismissError) { Text("Dismiss") }
                            }
                        }
                    }
                }
                if (state.canLoadEarlier) item(key = "earlier") { OutlinedButton(onClick = onLoadEarlier) { Text("Load earlier messages") } }
                if (messages.isEmpty()) {
                    item(key = "welcome") {
                        Text("What would you like me to work on?", style = MaterialTheme.typography.headlineSmall)
                        Text("Describe a bug, feature or review. Tools run automatically; approve each commit once before it is sent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(messages, key = { "message:${it.id}" }) { message ->
                    val assistant = message.platformType != null
                    Surface(color = if (assistant) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (assistant) "Engineering assistant" else "You", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            SelectionContainer {
                                if (assistant) {
                                    ChatMarkdown(message.effectiveContent(), adaptiveAnswerLength = message.effectiveContent().length, contentIdentity = "github:${state.chatId}:${message.id}", streaming = running && message.id == messages.lastOrNull()?.id, animateStreaming = false)
                                } else {
                                    Text(message.effectiveContent().let { if (it.startsWith("GitHub workspace:")) it.substringAfter("\n\n", it) else it })
                                }
                            }
                        }
                    }
                }
                item(key = "operations") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { operationsExpanded = !operationsExpanded }) { Text("Recent operations · ${state.events.size} · ${if (operationsExpanded) "Hide" else "Show"}") }
                        state.events.takeLast(if (operationsExpanded) 20 else 0).asReversed().forEach { event ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(event.modelToolName.ifBlank { event.toolName }.substringBefore("__").replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                                    val elapsed = event.startedAt?.let { start -> event.completedAt?.let { ((it - start).coerceAtLeast(0) / 1000.0).toString() + "s" } }.orEmpty()
                                    Text(listOf(event.status.lowercase(), elapsed, event.connectionNameSnapshot.orEmpty()).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                                    event.error?.takeIf(String::isNotBlank)?.let { Text(it.take(400), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        if (state.events.isEmpty()) Text("Investigation, changes and check results will appear here.", style = MaterialTheme.typography.bodySmall)
                        if (state.events.isNotEmpty() || workspace.staged.isNotEmpty() || workspace.patchStage != null) OutlinedButton(onClick = onOpenChanges) { Text("Review proposed changes") }
                    }
                }
            }
        }
    }
}
