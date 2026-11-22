package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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

/** Only one card occupies the composer; the complete queue remains directly reachable. */
@Composable
internal fun GitHubDecisionQueue(decisions: List<GitHubChatDecision>, content: @Composable (GitHubChatDecision) -> Unit) {
    if (decisions.isEmpty()) return
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val active = decisions.firstOrNull { it.id == selectedId }
        ?: decisions.firstOrNull { it.answer == null }
        ?: decisions.first()
    val index = decisions.indexOf(active)
    Column(Modifier.fillMaxWidth().testTag("github_decision_queue")) {
        if (decisions.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Choice ${index + 1} of ${decisions.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { selectedId = decisions[(index - 1 + decisions.size) % decisions.size].id }, modifier = Modifier.testTag("github_decision_previous")) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous choice")
                }
                IconButton(onClick = { selectedId = decisions[(index + 1) % decisions.size].id }, modifier = Modifier.testTag("github_decision_next")) {
                    Icon(Icons.Rounded.ChevronRight, "Next choice")
                }
            }
        }
        // Each queued choice owns its editor until the choice leaves the queue.
        decisions.forEach { decision ->
            key(decision.id) {
                val editor = rememberSaveableStateHolder()
                if (decision.id == active.id) editor.SaveableStateProvider(decision.id) { content(decision) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GitHubDecisionCard(decision: GitHubChatDecision, waiting: Boolean, onAnswer: (Int?, String?) -> Unit, onResume: () -> Unit, onStop: () -> Unit, runStartedMs: Long? = null, observedWorkMs: Long? = null, compact: Boolean = false) {
    var custom by rememberSaveable(decision.id) { mutableStateOf(false) }
    var answer by rememberSaveable(decision.id) { mutableStateOf("") }
    var expanded by rememberSaveable(decision.id) { mutableStateOf(false) }
    val showDetails = !compact || expanded
    val revisionInput = decision.options.any { it.enterValue }
    val validAnswer = if (revisionInput) dev.chungjungsoo.gptmobile.data.github.GitHubReviewComparison.acceptsBaselineInput(answer) else answer.isNotBlank()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val title = remember(decision.question) { dev.chungjungsoo.gptmobile.data.github.gitHubDecisionTitle(decision.question) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).gitHubDecisionOutline(shape, active = decision.answer == null).testTag("github_decision_${decision.id}"),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f))
    ) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.primaryContainer, colors.tertiaryContainer))).padding(start = 14.dp, end = if (compact) 4.dp else 14.dp, top = if (compact) 0.dp else 10.dp, bottom = if (compact) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = colors.onPrimaryContainer)
            Text(if (decision.answer == null) title else "Choice saved", style = MaterialTheme.typography.titleSmall, color = colors.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            if (compact) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("github_decision_details")) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide choice details" else "Show choice details", tint = colors.onPrimaryContainer)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showDetails) {
                decision.offeredAtMillis?.takeIf { !decision.automatic }?.let { started ->
                    val now by produceState(System.currentTimeMillis(), decision.id, decision.answeredAtMillis) {
                        while (decision.answer == null) {
                            value = System.currentTimeMillis()
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                    val seconds = ((decision.answeredAtMillis ?: now) - started).coerceAtLeast(0) / 1000
                    val label = when {
                        decision.answer != null -> "Decision time"
                        revisionInput -> "Waiting for baseline selection"
                        else -> "Waiting for your choice"
                    }
                    Text("$label · ${seconds / 60}m ${seconds % 60}s", style = MaterialTheme.typography.labelMedium)
                    runStartedMs?.takeIf { it <= started }?.let { beginning ->
                        Text("Elapsed ${dev.chungjungsoo.gptmobile.data.github.githubElapsedTime(beginning, decision.answeredAtMillis ?: now)}", style = MaterialTheme.typography.bodySmall)
                    }
                    observedWorkMs?.let { duration ->
                        Text("Recorded model and tool time before this choice · ${dev.chungjungsoo.gptmobile.data.github.githubElapsedTime(0, duration)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (decision.answer == null) Text("Work on this choice is paused. An explicit task deadline keeps running while you decide.", style = MaterialTheme.typography.bodySmall)
                }
                if (decision.answer == null && title != decision.question.trim()) Text(decision.question, style = MaterialTheme.typography.bodyMedium)
                if (decision.context.isNotBlank()) Text(decision.context, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (decision.answer == null) {
                val select: (Int) -> Unit = { index -> if (decision.options[index].enterValue) custom = true else onAnswer(index, null) }
                if (compact && !expanded) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val optionWidth = 128.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val columns = if (maxWidth >= optionWidth * 2 + 8.dp) 2 else 1
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = columns) {
                            decision.options.forEachIndexed { index, option ->
                                GitHubDecisionOptionButton(index, option, decision.recommended == index, false, { select(index) }, Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    decision.options.forEachIndexed { index, option ->
                        GitHubDecisionOptionButton(index, option, decision.recommended == index, true, { select(index) }, Modifier.fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { custom = !custom }, modifier = Modifier.weight(1f)) { Text(if (custom) "Hide custom answer" else "Write my own answer") }
                    if (compact) TextButton(onClick = onStop) { Text("Stop task") }
                }
                if (custom) {
                    OutlinedTextField(value = answer, onValueChange = { answer = it.take(2000) }, modifier = Modifier.fillMaxWidth().testTag("github_decision_custom"), label = { Text(if (revisionInput) "Commit, tag or branch" else "Your preference") }, minLines = if (compact) 1 else 2, maxLines = 5, supportingText = { Text(if (revisionInput) "Enter one revision, for example main or v1.0." else "${answer.length}/2000") })
                    Button(onClick = { onAnswer(null, answer) }, enabled = validAnswer, modifier = Modifier.fillMaxWidth()) { Text("Use my answer") }
                }
            } else {
                decision.bubble()?.let { GitHubDecisionBubble(it) }
                if (!waiting && !decision.cancelled) Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Continue with my choice") }
            }
            if (!compact || decision.answer != null) TextButton(onClick = onStop) { Text("Stop this task") }
        }
    }
}

@Composable
private fun GitHubDecisionOptionButton(index: Int, option: dev.chungjungsoo.gptmobile.data.github.GitHubDecisionOption, suggested: Boolean, showDetail: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("github_decision_option_$index"), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = if (showDetail) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.label, style = MaterialTheme.typography.labelLarge, maxLines = if (showDetail) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            if (suggested) Text("Suggested", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            if (showDetail && option.detail.isNotBlank()) Text(option.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GitHubLiveProgressCard(timeline: List<dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem>, task: String = "", events: List<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent> = emptyList(), expanded: Boolean = false, active: Boolean = true) {
    // Search receipts contain JSON and URLs; parse them off the composition thread.
    val search by produceState<GitHubSearchActivity?>(null, events) {
        value = withContext(Dispatchers.Default) { gitHubSearchActivity(events) }
    }
    val lines = remember(timeline, task, search != null, active) { gitHubActivityLines(timeline, task, searchActive = search != null, active = active) }
    val sourcePositions = remember(timeline) { timeline.mapIndexed { index, item -> (item.progressSequence ?: item.content) to index }.toMap() }
    fun identity(item: dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem): Any =
        item.progressId ?: "summary:${sourcePositions[item.progressSequence ?: item.content] ?: item.content}"
    var visibleEntries by remember(task, expanded) { androidx.compose.runtime.mutableIntStateOf(20) }
    // Saved history and rows revealed by "Show earlier" are already available;
    // reserve the entrance fade for new arrivals during this presentation.
    val seenEntries = remember(task) { lines.mapTo(mutableSetOf()) { identity(it) to it.statusSummary } }
    SideEffect { lines.forEach { seenEntries.add(identity(it) to it.statusSummary) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val visibleLines = remember(lines, visibleEntries) { gitHubVisibleActivityLines(lines, compact = true, entryLimit = visibleEntries) }
        val latestPreview = visibleLines.lastIndex.takeIf { active && gitHubHasActivePreview(timeline, search != null) && visibleLines.lastOrNull()?.statusSummary == false } ?: -1
        val motion = rememberGitHubProgressMotion(latestPreview >= 0)
        Column(Modifier.fillMaxWidth().testTag("github_live_activity"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visibleLines.size < lines.size) {
                TextButton(onClick = { visibleEntries = (visibleEntries + 20).coerceAtMost(lines.size) }, modifier = Modifier.testTag("github_activity_show_earlier")) { Text("Show earlier activity") }
            }
            visibleLines.forEachIndexed { index, item ->
                val lineIdentity = identity(item)
                androidx.compose.runtime.key(lineIdentity, item.statusSummary) {
                    // Arrival eligibility is independent of animation preferences;
                    // "Show earlier" only reveals retained rows and adds no reading budget.
                    val animateEntry = remember { (lineIdentity to item.statusSummary) !in seenEntries }
                    if (item.statusSummary) {
                        GitHubActivitySummary(item.content, animateEntry, lineIdentity, sourceOwned = !item.modelAuthored)
                    } else {
                        GitHubActivityPreview(item.content, latest = index == latestPreview, motion = motion, search = search.takeIf { index == latestPreview && dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.liveKey(item.content) == null }, animateEntry = animateEntry, lineIdentity = lineIdentity)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubActivitySummary(text: String, animateEntry: Boolean, lineIdentity: Any, sourceOwned: Boolean) {
    val entry = rememberGitHubActivityEntry(animateEntry)
    if (dev.chungjungsoo.gptmobile.data.github.GitHubRichSummaryPresentation.isRich(text)) {
        // The normal Markdown renderer already supports headings, bullets and
        // bounded tables. Keep earlier plain summaries on their measured Text path.
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .graphicsLayer { alpha = entry.value }
                .testTag("github_activity_rich_summary")
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
        ) {
            ChatMarkdown(
                content = text,
                contentIdentity = "github-rich-summary:$lineIdentity",
                streaming = false,
                animateStreaming = false,
                modifier = Modifier.fillMaxWidth()
            )
            if (sourceOwned) GitHubRichSummaryChart(text)
        }
    } else {
        val reportLayout = rememberGitHubTextLayout("summary" to lineIdentity, firstAppearanceAddsLines = animateEntry)
        GitHubActivityCrossfade(
            text,
            // Preserve complete historical paragraphs and measured text layouts.
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).graphicsLayer { alpha = entry.value }
        ) { displayed, current ->
            Text(
                displayed,
                onTextLayout = { if (current) reportLayout(it) },
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
