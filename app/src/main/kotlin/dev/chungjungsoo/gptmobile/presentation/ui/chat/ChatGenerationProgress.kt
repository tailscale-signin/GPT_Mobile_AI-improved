package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.delay

internal val LocalChatProgressInHeader = staticCompositionLocalOf { false }
internal val LocalGitHubApprovalPending = staticCompositionLocalOf { false }

/** One presentation and activity resolver for chat bubbles and the pinned GitHub header. */
@Composable
internal fun ChatGenerationProgress(
    toolEvents: List<ToolEvent>,
    timeline: List<AssistantTimelineItem>,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    statusOverride: String? = null,
    textSizeIncrease: Int = 0,
    startedAtMillis: Long? = null,
    completedAtMillis: Long? = null,
    engineeringJob: dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob? = null,
    githubPresentation: Boolean = false
) {
    if (githubPresentation) {
        GitHubLoadingHeader(
            activity = engineeringJob?.message ?: statusOverride ?: generationActivityLabel(toolEvents, timeline),
            active = engineeringJob?.let { !it.terminal } ?: active,
            startedAtMillis = engineeringJob?.createdAt?.times(1000)?.toLong() ?: startedAtMillis,
            completedAtMillis = engineeringJob?.finishedAt?.times(1000)?.toLong() ?: completedAtMillis,
            textSizeIncrease = textSizeIncrease,
            progress = engineeringJob?.progress,
            modifier = modifier.fillMaxWidth()
        )
        return
    }
    Column(modifier) {
        if (engineeringJob != null) {
            EngineeringJobProgress(engineeringJob, textSizeIncrease)
        } else if (active) {
            GenerationStatusText(toolEvents, timeline, statusOverride, textSizeIncrease, startedAtMillis, null)
            LinearProgressIndicator(
                Modifier.fillMaxWidth().clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        } else if (!statusOverride.isNullOrBlank()) {
            GenerationStatusText(toolEvents, timeline, statusOverride, textSizeIncrease, startedAtMillis, completedAtMillis, animate = false)
        }
    }
}

@Composable
private fun GenerationStatusText(toolEvents: List<ToolEvent>, timeline: List<AssistantTimelineItem>, statusOverride: String?, textSizeIncrease: Int, startedAtMillis: Long?, completedAtMillis: Long?, animate: Boolean = true) {
    val base = remember(toolEvents, timeline, statusOverride) {
        statusOverride ?: generationActivityLabel(toolEvents, timeline)
    }
    val style = MaterialTheme.typography.labelSmall.let { it.copy(fontSize = (it.fontSize.value + textSizeIncrease).sp) }
    Row(Modifier.fillMaxWidth().padding(bottom = if (animate) 8.dp else 0.dp)) {
        Text(
            text = base,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).alignByBaseline().clearAndSetSemantics {
                contentDescription = base
                liveRegion = LiveRegionMode.Polite
            }
        )
        startedAtMillis?.let { start ->
            GenerationElapsedTime(start, completedAtMillis, animate, style)
        }
    }
}

/** Only the timer recomposes each second; activity changes never resize the status row. */
@Composable
private fun RowScope.GenerationElapsedTime(start: Long, completed: Long?, active: Boolean, style: androidx.compose.ui.text.TextStyle) {
    var now by remember(start) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, active, start, completed) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            now = System.currentTimeMillis()
            while (active && completed == null) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }
    }
    Text(
        text = dev.chungjungsoo.gptmobile.data.github.githubElapsedTime(start, completed ?: now),
        style = style.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.alignByBaseline().padding(start = 12.dp)
    )
}

internal fun generationActivityLabel(toolEvents: List<ToolEvent>, timeline: List<AssistantTimelineItem>): String {
    val runningTool = toolEvents.lastOrNull {
        it.status == dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus.RUNNING ||
            it.status == dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus.PENDING
    }
    // Only changed observations invalidate this label; the visual timer and dots
    // do not need to rescan retained history.
    val activity = timeline.lastOrNull { it.type == AssistantTimelineItemType.TEXT || it.type == AssistantTimelineItemType.THINKING }
    val checkpoint = gitHubPublicSummary(timeline)?.content
        ?: timeline.lastOrNull { it.statusSummary }?.content
    return runningTool?.let { smartToolVerb(it.toolName) } ?: checkpoint ?: when (activity?.type) {
        AssistantTimelineItemType.TEXT -> "Writing"
        AssistantTimelineItemType.THINKING -> "Thinking"
        else -> "Preparing response"
    }
}
