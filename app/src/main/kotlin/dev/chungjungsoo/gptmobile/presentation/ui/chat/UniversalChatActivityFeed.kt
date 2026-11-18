package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import kotlinx.coroutines.delay

/** A public, mode-independent view of saved activity. Never renders tool arguments,
 * private thoughts, delegated content or diagnostic payloads.
 */
internal fun universalChatPublicCheckpoints(timeline: List<AssistantTimelineItem>): List<String> =
    timeline.asSequence()
        .filter { item ->
            item.type == AssistantTimelineItemType.NOTICE &&
                item.progressCheckpoint &&
                item.content.isNotBlank() &&
                (item.modelAuthored || item.statusSummary) &&
                item.delegationInvocationId == null &&
                item.delegationProfile == null &&
                !item.delegatedTool &&
                item.toolSequence == null &&
                item.recalledFacts.isEmpty() &&
                !isContextDiagnostic(item.content)
        }
        .mapNotNull { item ->
            item.content.lineSequence().map { it.trim().trimStart('#', '*', '-', ' ') }
                .firstOrNull(String::isNotBlank)?.take(280)
        }
        .distinctBy(GitHubProgressPresentation::lineKey)
        .toList()
        .takeLast(20)

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
    val lines = remember(checkpoints, currentActivity, taskFocus, showIntroduction, active) {
        buildList {
            if (active && showIntroduction && checkpoints.isEmpty() && taskFocus.isNotBlank()) {
                add(universalActivityOpening(taskFocus))
            }
            addAll(checkpoints)
            if (active && currentActivity.isNotBlank() && lastOrNull() != currentActivity) add(currentActivity)
        }.distinctBy { GitHubProgressPresentation.semanticActivityKey(it) ?: GitHubProgressPresentation.lineKey(it) }
            .takeLast(20)
    }
    if (lines.isEmpty() || !active && !expanded) return
    val visible = if (expanded) lines else lines.takeLast(2)
    val motion = rememberGitHubProgressMotion(active && animate)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        visible.forEachIndexed { index, line ->
            key("universal:$line") {
                GitHubActivityPreview(
                    raw = line,
                    latest = active && index == visible.lastIndex,
                    motion = motion,
                    animateEntry = animate,
                    lineIdentity = "universal:$line"
                )
            }
        }
    }
}
