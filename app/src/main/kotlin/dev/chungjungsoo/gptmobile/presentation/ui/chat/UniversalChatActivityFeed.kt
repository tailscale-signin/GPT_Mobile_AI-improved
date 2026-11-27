package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import kotlinx.coroutines.delay

/** Coalescing and rendering must agree on which public updates share a row. */
internal fun universalActivityLineIdentity(line: String): String {
    val text = line.trim()
    // A multiline finding can start with a heartbeat or counter while its
    // following observations still need their own row and expansion state.
    if ('\n' !in text && '\r' !in text) {
        GitHubProgressPresentation.liveKey(text)?.let { return "counter:$it" }
        GitHubProgressPresentation.semanticActivityKey(text)?.let { return "story:$it" }
    }
    return "text:${GitHubProgressPresentation.lineKey(text)}"
}

/** Deduplicate old counter ticks without discarding distinct public findings. */
internal fun universalCoalescedActivityLines(lines: List<String>): List<String> {
    val current = linkedMapOf<String, String>()
    lines.forEach { line ->
        val identity = universalActivityLineIdentity(line)
        if (identity.startsWith("counter:")) {
            // Move the latest measured count into its actual activation order.
            current.remove(identity)
            current[identity] = line
        } else {
            // Repeated prose is not new evidence; retain the original wording.
            current.putIfAbsent(identity, line)
        }
    }
    return current.values.toList().takeLast(20)
}

/** A public, mode-independent view of saved activity. Never renders tool arguments,
 * private thoughts, delegated content or diagnostic payloads.
 */
internal fun universalChatPublicCheckpoints(timeline: List<AssistantTimelineItem>): List<String> =
    timeline.asSequence()
        .mapNotNull(::publicCheckpointLine)
        .toList()
        .let(::universalCoalescedActivityLines)

internal fun publicCheckpointLine(item: AssistantTimelineItem): String? {
    val visible = item.type == AssistantTimelineItemType.NOTICE &&
        item.progressCheckpoint &&
        item.content.isNotBlank() &&
        (item.modelAuthored || item.statusSummary) &&
        item.delegationInvocationId == null &&
        item.delegationProfile == null &&
        !item.delegatedTool &&
        item.toolSequence == null &&
        item.toolMetrics == null &&
        item.recalledFacts.isEmpty() &&
        !isContextDiagnostic(item.content)
    if (!visible) return null
    // The preview owns visual line limits and expansion. Keep every public finding
    // here so a shared headline cannot erase distinct bodies or their final caveat.
    return item.content.trim()
}

/** A request-specific opening without pretending a tool or source has been executed. */
internal fun universalActivityOpening(task: String): String {
    val focus = GitHubProgressPresentation.taskLabel(task).take(74)
    return when {
        Regex("""(?i)\b(compare|versus|difference)\b""").containsMatchIn(task) -> "Organizing the comparison: $focus"
        Regex("""(?i)\b(design|draft|write|create)\b""").containsMatchIn(task) -> "Preparing the requested work: $focus"
        Regex("""(?i)\b(find|search|research|investigate|analy[sz]e|examine)\b""").containsMatchIn(task) -> "Examining the request: $focus"
        else -> "Preparing an answer to: $focus"
    }
}

/** Reuses the GitHub line transitions for every chat without enabling GitHub's
 * completion gate, which would hold back normal streamed answer tokens.
 */
@Composable
internal fun UniversalChatActivityFeed(
    task: String?,
    timeline: List<AssistantTimelineItem>,
    toolEvents: List<ToolEvent>,
    active: Boolean,
    animate: Boolean,
    expanded: Boolean
) {
    val checkpoints = remember(timeline) { universalChatPublicCheckpoints(timeline) }
    val currentActivity = remember(toolEvents, timeline) {
        generationActivityLabel(toolEvents, timeline).lineSequence()
            .firstOrNull().orEmpty().trim().take(200)
    }
    val taskFocus = remember(task) { task.orEmpty().replace(Regex("\\s+"), " ").trim().take(92) }
    var showIntroduction by remember(task, active) { mutableStateOf(false) }
    LaunchedEffect(task, active) {
        if (active) {
            delay(2000)
            showIntroduction = true
        } else {
            showIntroduction = false
        }
    }
    val hasToolActivity = toolEvents.isNotEmpty()
    val lines = remember(checkpoints, currentActivity, taskFocus, showIntroduction, active, hasToolActivity, expanded) {
        buildList {
            if (active && showIntroduction && (hasToolActivity || expanded) && checkpoints.isEmpty() && taskFocus.isNotBlank()) {
                add(universalActivityOpening(taskFocus))
            }
            addAll(checkpoints)
            if (active && (hasToolActivity || expanded) && currentActivity.isNotBlank() && lastOrNull() != currentActivity) add(currentActivity)
        }.let(::universalCoalescedActivityLines)
    }
    // This is a source-backed execution visualization, not model-generated facts.
    // Show it only in expanded activity to keep everyday chats uncluttered.
    val receiptSummary = remember(toolEvents) { observedToolReceiptSummary(toolEvents) }
    if (!active && !expanded) return
    if (lines.isEmpty() && (!expanded || receiptSummary == null)) return
    val visible = if (expanded) lines else lines.takeLast(2)
    val motion = rememberGitHubProgressMotion(active && animate)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        visible.forEachIndexed { index, line ->
            val identity = "universal:" + universalActivityLineIdentity(line)
            key(identity) {
                GitHubActivityPreview(
                    raw = line,
                    latest = active && index == visible.lastIndex,
                    motion = motion,
                    animateEntry = animate,
                    lineIdentity = identity
                )
            }
        }
        if (expanded && receiptSummary != null) {
            ObservedToolReceiptChart(receiptSummary)
        }
    }
}

/**
 * A typed source-owned aggregate for the universal activity feed.
 *
 * Every count comes from a retained ToolEvent. We do not inspect or display
 * model-generated prose, tool arguments, URLs or credentials. Only a bound local
 * decision receipt can project an old failure-shaped record as a user wait.
 * An execution's COMPLETED state does not establish valid results or task
 * acceptance. Last known state wins for the same stable run/call identity.
 */
internal data class ObservedToolReceiptSummary(
    val completed: Int,
    val failed: Int,
    val canceled: Int,
    val ongoing: Int,
    val unknown: Int,
    val waiting: Int = 0
) {
    val total: Int get() = completed + failed + canceled + ongoing + unknown + waiting
}

internal fun observedToolReceiptSummary(events: List<ToolEvent>): ObservedToolReceiptSummary? {
    if (events.isEmpty()) return null
    val unique = events.groupBy { event ->
        event.runId to event.callId.takeIf(String::isNotBlank).orEmpty().ifBlank { event.eventId }
    }.values.mapNotNull { group ->
        group.maxWithOrNull(
            compareBy<ToolEvent> { it.sequence }
                .thenBy { it.completedAt ?: it.startedAt ?: Long.MIN_VALUE }
        )
    }
    // A chart is useful for a genuine collection, not one or two tool calls.
    if (unique.size < 3) return null
    val completed = unique.count { it.status == ToolEventStatus.COMPLETED && !it.isError }
    val failed = unique.count { (it.status == ToolEventStatus.FAILED || it.status == ToolEventStatus.COMPLETED && it.isError) && !dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.waitingForUserChoice(it) }
    val canceled = unique.count { it.status == ToolEventStatus.CANCELED }
    val waiting = unique.count { dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.waitingForUserChoice(it) }
    val ongoing = unique.count { (it.status == ToolEventStatus.RUNNING || it.status == ToolEventStatus.PENDING) && !dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.waitingForUserChoice(it) }
    return ObservedToolReceiptSummary(
        completed = completed,
        failed = failed,
        canceled = canceled,
        ongoing = ongoing,
        unknown = unique.size - completed - failed - canceled - ongoing - waiting,
        waiting = waiting
    )
}

/** A user-triggered export contains only fixed status labels and observed counts. */
internal fun toolReceiptCsv(summary: ObservedToolReceiptSummary): String =
    buildString {
        append("Execution state,Observed calls\r\n")
        listOf(
            "Completed" to summary.completed,
            "Failed" to summary.failed,
            "Canceled" to summary.canceled,
            "Pending or running" to summary.ongoing,
            "Waiting for your choice" to summary.waiting,
            "Unknown state" to summary.unknown
        ).forEach { (label, count) ->
            append(label).append(',').append(count).append("\r\n")
        }
        append("Total,").append(summary.total).append("\r\n")
    }

@Composable
private fun ObservedToolReceiptChart(summary: ObservedToolReceiptSummary) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val rows = listOf(
        Triple("Completed", summary.completed, scheme.primary),
        Triple("Failed", summary.failed, scheme.error),
        Triple("Canceled", summary.canceled, scheme.tertiary),
        Triple("Pending or running", summary.ongoing, scheme.secondary),
        Triple("Waiting for your choice", summary.waiting, scheme.tertiary),
        Triple("Unknown state", summary.unknown, scheme.onSurfaceVariant)
    ).filter { it.second > 0 }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag("universal_tool_receipt_chart"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Tool execution overview · ${summary.total} calls",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(toolReceiptCsv(summary))) },
                modifier = Modifier.testTag("universal_tool_receipt_copy_csv")
            ) { Text("Copy CSV") }
        }
        rows.forEach { (label, count, color) ->
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "$label: $count of ${summary.total} observed tool calls"
                },
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(scheme.surfaceVariant)) {
                    Box(
                        Modifier.fillMaxWidth(count.toFloat() / summary.total.coerceAtLeast(1))
                            .height(6.dp).background(color)
                    )
                }
            }
        }
        Text(
            "Observed tool states only; they do not verify answer accuracy or task completion.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}
