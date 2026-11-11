package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubChatPresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GitHubDecisionCard(decision: GitHubChatDecision, waiting: Boolean, onAnswer: (Int?, String?) -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    var custom by rememberSaveable(decision.id) { mutableStateOf(false) }
    var answer by rememberSaveable(decision.id) { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val title = remember(decision.question) { dev.chungjungsoo.gptmobile.data.github.gitHubDecisionTitle(decision.question) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).gitHubDecisionOutline(shape, active = decision.answer == null).testTag("github_decision_${decision.id}"),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f))
    ) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.primaryContainer, colors.tertiaryContainer))).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = colors.onPrimaryContainer)
            Text(if (decision.answer == null) title else "Choice saved", style = MaterialTheme.typography.titleSmall, color = colors.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (decision.answer == null && title != decision.question.trim()) Text(decision.question, style = MaterialTheme.typography.bodyMedium)
            if (decision.context.isNotBlank()) Text(decision.context, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (decision.answer == null) {
                decision.options.forEachIndexed { index, option ->
                    OutlinedButton(onClick = { onAnswer(index, null) }, modifier = Modifier.fillMaxWidth().testTag("github_decision_option_$index"), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(option.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (decision.recommended == index) {
                                    Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = RoundedCornerShape(8.dp)) {
                                        Text("Suggested", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (option.detail.isNotBlank()) Text(option.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { custom = !custom }) { Text(if (custom) "Hide custom answer" else "Write my own answer") }
                if (custom) {
                    OutlinedTextField(value = answer, onValueChange = { answer = it.take(2000) }, modifier = Modifier.fillMaxWidth().testTag("github_decision_custom"), label = { Text("Your preference") }, minLines = 2, maxLines = 5, supportingText = { Text("${answer.length}/2000") })
                    Button(onClick = { onAnswer(null, answer) }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Use my answer") }
                }
            } else {
                decision.bubble()?.let { GitHubDecisionBubble(it) }
                if (!waiting && !decision.cancelled) Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Continue with my choice") }
            }
            TextButton(onClick = onStop) { Text("Stop this task") }
        }
    }
}

@Composable
internal fun GitHubLiveProgressCard(timeline: List<dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem>, task: String = "", events: List<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent> = emptyList(), expanded: Boolean = false, active: Boolean = true) {
    // Search receipts contain JSON and URLs; parse them off the composition thread.
    val search by produceState<GitHubSearchActivity?>(null, events) {
        value = withContext(Dispatchers.Default) { gitHubSearchActivity(events) }
    }
    val lines = remember(timeline, task, search != null) { gitHubActivityLines(timeline, task, searchActive = search != null) }
    var visibleEntries by remember(task, expanded) { androidx.compose.runtime.mutableIntStateOf(20) }
    // Saved history and rows revealed by "Show earlier" are already available;
    // reserve the entrance fade for new arrivals during this presentation.
    val seenEntries = remember(task) { lines.mapTo(mutableSetOf()) { (it.progressId ?: it.content) to it.statusSummary } }
    SideEffect { lines.forEach { seenEntries.add((it.progressId ?: it.content) to it.statusSummary) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val visibleLines = remember(lines, visibleEntries) { gitHubVisibleActivityLines(lines, compact = true, entryLimit = visibleEntries) }
        val latestPreview = latestGitHubActivityIndex(visibleLines).takeIf { active && it >= 0 && !visibleLines[it].statusSummary } ?: -1
        val motion = rememberGitHubProgressMotion(latestPreview >= 0)
        Column(Modifier.fillMaxWidth().testTag("github_live_activity"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visibleLines.size < lines.size) {
                TextButton(onClick = { visibleEntries = (visibleEntries + 20).coerceAtMost(lines.size) }, modifier = Modifier.testTag("github_activity_show_earlier")) { Text("Show earlier activity") }
            }
            visibleLines.forEachIndexed { index, item ->
                androidx.compose.runtime.key(item.progressId ?: item.content, item.statusSummary) {
                    val animateEntry = remember { ((item.progressId ?: item.content) to item.statusSummary) !in seenEntries }
                    if (item.statusSummary) {
                        GitHubActivitySummary(item.content, animateEntry)
                    } else {
                        GitHubActivityPreview(item.content, latest = index == latestPreview, motion = motion, search = search.takeIf { index == latestPreview && dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.liveKey(item.content) == null }, animateEntry = animateEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubActivitySummary(text: String, animateEntry: Boolean) {
    val entry = rememberGitHubActivityEntry(animateEntry)
    GitHubActivityCrossfade(
        text,
        // Preserve the complete authored paragraphs. Fade only the drawing so
        // neither scrolling nor neighbouring rows move during the entrance.
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).graphicsLayer { alpha = entry.value }
    ) { displayed, current ->
        Text(
            displayed,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().then(
                if (current) {
                    Modifier.testTag("github_activity_summary")
                        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                } else {
                    Modifier
                }
            )
        )
    }
}

@Composable
internal fun GitHubChatPresenceEffect(chatId: Int?) {
    val owner = remember { java.util.UUID.randomUUID().toString() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle, chatId) {
        fun update() {
            if (chatId != null && chatId > 0 && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) GitHubChatPresence.show(owner, chatId) else GitHubChatPresence.hide(owner)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            GitHubChatPresence.hide(owner)
        }
    }
}
