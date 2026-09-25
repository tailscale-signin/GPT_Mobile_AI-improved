package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.theme.defaultSpatialSpec
import dev.chungjungsoo.gptmobile.presentation.theme.fastEffectsSpec

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineExecutionTrace(events: List<ToolEvent>, timeline: List<AssistantTimelineItem>, contentIdentity: Any) {
    val recalled = timeline.flatMap { it.recalledFacts }.distinctBy { it.id }
    if (events.isEmpty() && recalled.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (recalled.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recalled.forEach { fact ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("🧠 Recalled: ${fact.label}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        events.sortedBy { it.sequence }.forEach { event ->
            val metrics = timeline.firstOrNull { it.toolSequence == event.sequence }?.toolMetrics
            var expanded by rememberSaveable(contentIdentity.toString(), event.eventId) { mutableStateOf(false) }
            val status = when (event.status) {
                ToolEventStatus.RUNNING, ToolEventStatus.PENDING -> "Running"
                ToolEventStatus.FAILED -> "Failed"
                ToolEventStatus.CANCELED -> "Canceled"
                else -> "Completed"
            }
            val summary = buildString {
                append("⚙ Tool: ${event.toolName} · $status")
                metrics?.durationMs?.let { append(" · ${it}ms") }
                metrics?.resultBytes?.let { append(" · +$it bytes") }
                if (metrics?.shared == true) append(" · Shared")
            }
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(18.dp),
                color = if (event.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().animateContentSize(defaultSpatialSpec())
                    .semantics { contentDescription = "$summary. ${if (expanded) "Collapse" else "Expand"} tool details" }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(summary, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(defaultSpatialSpec()) + fadeIn(fastEffectsSpec()),
                        exit = shrinkVertically(defaultSpatialSpec()) + fadeOut(fastEffectsSpec())
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            metrics?.let {
                                Text("Arguments: ${it.argumentsCharacters} characters · ${it.argumentsBytes} UTF-8 bytes", style = MaterialTheme.typography.bodySmall)
                                it.estimatedResultTokens?.let { tokens ->
                                    Text("Result: approximately $tokens tokens (character estimate, not billed usage).", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    when {
                                        it.timingSource == "gateway" -> "Gateway-reported duration. Response payload size was not supplied."
                                        it.shared -> "Reused a result from this conversation turn. Time measures delivery or waiting for the shared result."
                                        else -> "Time measures this tool execution. Payload sizes are measured before output clipping."
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            ToolTraceBlock(events = listOf(event))
                        }
                    }
                }
            }
        }
    }
}
