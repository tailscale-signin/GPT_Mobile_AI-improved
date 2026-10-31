package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    completedAtMillis: Long? = null
) {
    Column(modifier) {
        if (active) {
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dots by remember { mutableIntStateOf(1) }
    var phase by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, animate, startedAtMillis) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            now = System.currentTimeMillis()
            if (!animate) return@repeatOnLifecycle
            var ticks = 0
            while (true) {
                delay(500)
                now = System.currentTimeMillis()
                dots = dots % 3 + 1
                ticks++
                if (ticks % 10 == 0) phase++
            }
        }
    }

    val base = remember(phase, toolEvents, timeline, statusOverride) {
        statusOverride ?: generationActivityLabel(toolEvents, timeline)
    }
    val style = MaterialTheme.typography.labelSmall.let { it.copy(fontSize = (it.fontSize.value + textSizeIncrease).sp) }
    Row(Modifier.fillMaxWidth().padding(bottom = if (animate) 8.dp else 0.dp)) {
        Text(
            text = if (animate) base.removeSuffix("...").removeSuffix(".") + ".".repeat(dots) else base,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).alignByBaseline().clearAndSetSemantics {
                contentDescription = base
                liveRegion = LiveRegionMode.Polite
            }
        )
        startedAtMillis?.let { start ->
            Text(
                text = dev.chungjungsoo.gptmobile.data.github.githubElapsedTime(start, completedAtMillis ?: now),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().padding(start = 12.dp)
            )
        }
    }
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
