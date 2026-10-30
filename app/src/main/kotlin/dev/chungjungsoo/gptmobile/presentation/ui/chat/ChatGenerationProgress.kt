package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.delay

internal val LocalChatProgressInHeader = staticCompositionLocalOf { false }

/** One presentation and activity resolver for chat bubbles and the pinned GitHub header. */
@Composable
internal fun ChatGenerationProgress(
    toolEvents: List<ToolEvent>,
    timeline: List<AssistantTimelineItem>,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    statusOverride: String? = null
) {
    Column(modifier) {
        if (active) {
            GenerationStatusText(toolEvents, timeline, statusOverride)
            LinearProgressIndicator(
                Modifier.fillMaxWidth().clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        } else if (!statusOverride.isNullOrBlank()) {
            Text(statusOverride, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GenerationStatusText(toolEvents: List<ToolEvent>, timeline: List<AssistantTimelineItem>, statusOverride: String?) {
    var dots by remember { mutableIntStateOf(1) }
    var phase by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var ticks = 0
            while (true) {
                delay(500)
                dots = dots % 3 + 1
                ticks++
                if (ticks % 10 == 0) phase++
            }
        }
    }

    val base = remember(phase, toolEvents, timeline, statusOverride) {
        statusOverride ?: generationActivityLabel(toolEvents, timeline)
    }
    val suffix = ".".repeat(dots)

    Text(
        text = base.removeSuffix("...").removeSuffix(".") + suffix,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp).clearAndSetSemantics {
            // Announce activity changes once, not every animated punctuation tick.
            contentDescription = base
            liveRegion = LiveRegionMode.Polite
        }
    )
}

internal fun generationActivityLabel(toolEvents: List<ToolEvent>, timeline: List<AssistantTimelineItem>): String {
    val runningTool = toolEvents.lastOrNull {
        it.status == dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus.RUNNING ||
            it.status == dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus.PENDING
    }
    // Refresh from actual activity every five seconds, rather than inventing work phases.
    val activity = timeline.lastOrNull { it.type == AssistantTimelineItemType.TEXT || it.type == AssistantTimelineItemType.THINKING }
    val checkpoint = timeline.lastOrNull { it.statusSummary }?.content
        ?: timeline.lastOrNull { it.progressCheckpoint && it.modelAuthored }?.content
    return runningTool?.let { smartToolVerb(it.toolName) } ?: checkpoint ?: when (activity?.type) {
        AssistantTimelineItemType.TEXT -> "Writing"
        AssistantTimelineItemType.THINKING -> "Thinking"
        else -> "Preparing response"
    }
}
