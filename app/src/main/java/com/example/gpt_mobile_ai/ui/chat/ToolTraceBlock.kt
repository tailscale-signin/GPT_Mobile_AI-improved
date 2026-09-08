package com.example.gpt_mobile_ai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.gpt_mobile_ai.R
import com.example.gpt_mobile_ai.data.database.entity.ToolEvent
import com.example.gpt_mobile_ai.ui.theme.GPTMobileTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Represents the strongly-typed state of a tool call.
 * This ensures exhaustive handling in the UI and prevents unhandled states.
 */
sealed interface ToolCallState {
    data object Running : ToolCallState
    data class Completed(val result: String?) : ToolCallState
    data class Failed(val error: String) : ToolCallState
    data object Canceled : ToolCallState
}

/**
 * Extension function to map the database entity status to our UI state.
 */
fun ToolEvent.toToolCallState(): ToolCallState = when (this.status) {
    "running" -> ToolCallState.Running
    "completed" -> ToolCallState.Completed(this.result)
    "failed" -> ToolCallState.Failed(this.error ?: "Unknown error")
    "canceled" -> ToolCallState.Canceled
    else -> ToolCallState.Failed("Unknown status: ${this.status}")
}

/**
 * Defines the visual and semantic properties of a tool.
 * This makes adding new tools a plug-and-play operation.
 */
interface ToolDefinition {
    fun matches(toolName: String): Boolean
    val iconResId: Int
    val color: Color

    @Composable
    fun getDisplayName(): String
}

/**
 * Registry of all available tools in the app.
 */
val ToolRegistry = listOf(
    object : ToolDefinition {
        override fun matches(toolName: String) = toolName.contains("github", ignoreCase = true)
        override val iconResId = R.drawable.ic_github_logo
        override val color = Color(0xFF24292E)

        @Composable
        override fun getDisplayName() = stringResource(R.string.github)
    },
    object : ToolDefinition {
        override fun matches(toolName: String) = toolName.contains("brave", ignoreCase = true)
        override val iconResId = R.drawable.ic_brave_logo
        override val color = Color(0xFFFF4F00)

        @Composable
        override fun getDisplayName() = "Brave"
    },
    object : ToolDefinition {
        override fun matches(toolName: String) = toolName.contains("mcp", ignoreCase = true)
        override val iconResId = R.drawable.ic_mcp_logo
        override val color = Color(0xFF0052CC)

        @Composable
        override fun getDisplayName() = stringResource(R.string.mcp_server)
    },
    object : ToolDefinition {
        override fun matches(toolName: String) =
            toolName.contains("web", ignoreCase = true) ||
            toolName.contains("search", ignoreCase = true)
        override val iconResId = R.drawable.ic_web_search
        override val color = Color(0xFF4285F4)

        @Composable
        override fun getDisplayName() = stringResource(R.string.search)
    },
    object : ToolDefinition {
        override fun matches(toolName: String) = toolName.contains("calculate", ignoreCase = true)
        override val iconResId = R.drawable.ic_calculator
        override val color = Color(0xFF0F9D58)

        @Composable
        override fun getDisplayName() = "Calculator"
    }
)

/**
 * Fallback tool definition for unknown tools.
 */
val DefaultToolDefinition = object : ToolDefinition {
    override fun matches(toolName: String) = true
    override val iconResId = R.drawable.ic_gpt_mobile_foreground
    override val color = Color.Gray

    @Composable
    override fun getDisplayName() = "System"
}

@Composable
fun ToolTraceBlock(
    toolEvent: ToolEvent,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expand_icon_rotation"
    )

    val toolState = toolEvent.toToolCallState()
    val toolDef = ToolRegistry.firstOrNull { it.matches(toolEvent.toolName) } ?: DefaultToolDefinition
    val displayName = toolDef.getDisplayName()

    // Format the summary text (e.g., "GitHub — Search Code")
    val summaryText = buildString {
        append(displayName)
        val specificAction = toolEvent.toolName.split("__")
            .lastOrNull()
            ?.replace("_", " ")
            ?.replaceFirstChar { it.uppercase() }

        if (specificAction != null &&
            specificAction.isNotBlank() &&
            !specificAction.equals(displayName, ignoreCase = true)
        ) {
            append(" — $specificAction")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant) // Darker background, no alpha
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Tool Icon
            Icon(
                painter = painterResource(id = toolDef.iconResId),
                contentDescription = displayName,
                modifier = Modifier.size(24.dp),
                tint = toolDef.color
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Tool Summary and Status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Status Indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (toolState) {
                        is ToolCallState.Running -> MaterialTheme.colorScheme.primary
                        is ToolCallState.Completed -> MaterialTheme.colorScheme.primary
                        is ToolCallState.Failed -> MaterialTheme.colorScheme.error
                        is ToolCallState.Canceled -> MaterialTheme.colorScheme.outline
                    }

                    if (toolState is ToolCallState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = statusColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    val statusText = when (toolState) {
                        is ToolCallState.Running -> stringResource(R.string.tool_trace_status_running)
                        is ToolCallState.Completed -> stringResource(R.string.tool_trace_status_completed)
                        is ToolCallState.Failed -> stringResource(R.string.tool_trace_status_failed)
                        is ToolCallState.Canceled -> stringResource(R.string.tool_trace_status_canceled)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }

            // Expand/Collapse Icon
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) {
                    stringResource(R.string.tool_trace_collapse_content_description)
                } else {
                    stringResource(R.string.tool_trace_expand_content_description)
                },
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Expanded Details
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Arguments
                if (toolEvent.arguments.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.tool_trace_arguments),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = toolEvent.arguments,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Result or Error
                when (toolState) {
                    is ToolCallState.Completed -> {
                        if (!toolState.result.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.tool_trace_result),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                text = toolState.result,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            )
                        }
                    }
                    is ToolCallState.Failed -> {
                        Text(
                            text = stringResource(R.string.tool_trace_error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = toolState.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        )
                    }
                    else -> {} // Running or Canceled don't show results
                }

                // Timing
                Spacer(modifier = Modifier.height(8.dp))
                val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                val timeString = formatter.format(Instant.ofEpochMilli(toolEvent.timestamp))
                Text(
                    text = "${stringResource(R.string.tool_trace_timing_started_at)} $timeString",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ToolTraceBlockPreview() {
    GPTMobileTheme {
        ToolTraceBlock(
            toolEvent = ToolEvent(
                id = 1,
                messageId = 1,
                toolCallId = "call_123",
                toolName = "mcp__github__search_code",
                arguments = "{\"query\": \"ToolTraceBlock\"}",
                status = "completed",
                result = "Found 1 result in ToolTraceBlock.kt",
                error = null,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ToolTraceBlockRunningPreview() {
    GPTMobileTheme {
        ToolTraceBlock(
            toolEvent = ToolEvent(
                id = 2,
                messageId = 1,
                toolCallId = "call_456",
                toolName = "calculate_expression",
                arguments = "{\"expression\": \"2 + 2\"}",
                status = "running",
                result = null,
                error = null,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
