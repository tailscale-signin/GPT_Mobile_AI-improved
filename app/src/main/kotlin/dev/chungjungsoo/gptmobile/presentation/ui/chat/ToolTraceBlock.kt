Warning: truncated output (original token count: 9145)
Total output lines: 827

package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.displayResult
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventError
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.time.Instant
import java.util.Locale

private const val TOOL_TRACE_TEXT_LIMIT = 1024

sealed interface ToolCallState {
    data object Running : ToolCallState
    data object Completed : ToolCallState
    data class Failed(val isError: Boolean) : ToolCallState
    data object Canceled : ToolCallState
}

fun ToolEvent.toToolCallState(): ToolCallState = when {
    status == ToolEventStatus.RUNNING || status == ToolEventStatus.PENDING -> ToolCallState.Running
    status == ToolEventStatus.FAILED || isError -> ToolCallState.Failed(isError)
    status == ToolEventStatus.CANCELED -> ToolCallState.Canceled
    else -> ToolCallState.Completed
}

interface ToolDefinition {
    fun matches(toolName: String, connectionName: String?, connectionUid: String?): Boolean
    val serviceNameRes: Int
    val monogram: String
    val badgeColor: Color
    val textColor: Color get() = Color.White
    fun getDisplayNameRes(rawName: String): Int? = null
}

object GitHubTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) =
        connectionName?.contains("github", ignoreCase = true) == true ||
            connectionUid?.contains("github", ignoreCase = true) == true ||
            toolName.startsWith("github", ignoreCase = true)
    override val serviceNameRes = R.string.tool_name_github
    override val monogram = "GH"
    override val badgeColor = Color(0xFF24292F)
}

object BraveTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) =
        connectionName?.contains("brave", ignoreCase = true) == true ||
            connectionUid?.contains("brave", ignoreCase = true) == true ||
            toolName.contains("brave", ignoreCase = true)
    override val serviceNameRes = R.string.tool_name_brave
    override val monogram = "B"
    override val badgeColor = Color(0xFFFB542B)
}

object MicrosoftTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) =
        connectionName?.contains("microsoft", ignoreCase = true) == true ||
            connectionUid?.contains("microsoft", ignoreCase = true) == true ||
            toolName.contains("microsoft", ignoreCase = true)
    override val serviceNameRes = R.string.tool_name_microsoft
    override val monogram = "MS"
    override val badgeColor = Color(0xFF0078D4)
}

object McpTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) =
        connectionName?.contains("mcp", ignoreCase = true) == true ||
            connectionUid?.contains("mcp", ignoreCase = true) == true ||
            toolName.contains("mcp", ignoreCase = true)
    override val serviceNameRes = R.string.tool_name_mcp
    override val monogram = "M"
    override val badgeColor = Color(0xFF00838F)
}

object WebTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) =
        toolName.contains("search", ignoreCase = true) || toolName.contains("web", ignoreCase = t…6145 tokens truncated…tionSeconds(event) ?: return null
    return "$seconds s"
}

internal fun toolDurationSeconds(event: ToolEvent): Long? {
    val startedAt = event.startedAt ?: return null
    val completedAt = event.completedAt ?: return null
    return (completedAt - startedAt).coerceAtLeast(0)
}

@Composable
private fun toolTimingLabel(event: ToolEvent, labels: ToolTraceLabels): String? {
    val startedAt = event.startedAt
    val completedAt = event.completedAt
    return when {
        startedAt != null && completedAt != null -> {
            val seconds = toolDurationSeconds(event) ?: return null
            "${Instant.ofEpochSecond(startedAt)} - ${Instant.ofEpochSecond(completedAt)} (${pluralStringResource(R.plurals.duration_seconds, seconds.toInt(), seconds)})"
        }
        startedAt != null -> "${labels.startedAt} ${Instant.ofEpochSecond(startedAt)}"
        else -> null
    }
}

internal fun formatToolTraceMarkdown(events: List<ToolEvent>, labels: ToolTraceLabels = ToolTraceLabels.Default): String {
    if (events.isEmpty()) return ""
    return buildString {
        appendLine("## ${labels.exportHeader(events.size)}")
        filterToolEvents(events, "").forEach { event ->
            appendLine()
            appendLine("### ${event.sequence + 1}. ${event.toolName}")
            appendLine("- ${labels.status}: ${event.status}")
            appendLine("- ${labels.callId}: ${event.callId}")
            connectionLabel(event)?.let { appendLine("- ${labels.connection}: $it") }
            appendLine("- ${labels.tool}: ${event.toolName}")
            if (event.modelToolName != event.toolName) appendLine("- ${labels.modelTool}: ${event.modelToolName}")
            timingLabel(event, labels)?.let { appendLine("- ${labels.timing}: $it") }
            event.error?.takeIf { it.isNotBlank() }?.let { appendLine("- ${labels.error}: ${boundedText(it)}") }
            appendIndentedBlock(labels.arguments, event.arguments)
            event.displayResult()?.takeIf { it.isNotBlank() }?.let { appendIndentedBlock(labels.result, it) }
        }
    }.trimEnd()
}

private fun StringBuilder.appendIndentedBlock(label: String, value: String) {
    appendLine("- $label:")
    boundedText(value).lineSequence().forEach { appendLine("    $it") }
}

private fun timingLabel(event: ToolEvent, labels: ToolTraceLabels): String? {
    val startedAt = event.startedAt
    val completedAt = event.completedAt
    return when {
        startedAt != null && completedAt != null -> "${Instant.ofEpochSecond(startedAt)} - ${Instant.ofEpochSecond(completedAt)} (${formatToolDuration(event)})"
        startedAt != null -> "${labels.startedAt} ${Instant.ofEpochSecond(startedAt)}"
        else -> null
    }
}

@Composable
private fun toolEventErrorText(error: String): String = when (error) {
    ToolEventError.INTERRUPTED_APP_STOPPED -> stringResource(R.string.tool_event_error_interrupted_app_stopped)
    else -> boundedText(error)
}

private fun connectionLabel(event: ToolEvent): String? {
    val name = event.connectionNameSnapshot?.takeIf { it.isNotBlank() }
    val uid = event.connectionUidSnapshot?.takeIf { it.isNotBlank() }
    return when {
        name != null && uid != null -> "$name ($uid)"
        name != null -> name
        uid != null -> uid
        else -> null
    }
}

private fun boundedText(value: String): String {
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
    return if (normalized.length <= TOOL_TRACE_TEXT_LIMIT) normalized else normalized.take(TOOL_TRACE_TEXT_LIMIT) + "..."
}

data class ToolTraceLabels(
    val expandToolTrace: String,
    val collapseToolTrace: String,
    val expand: String,
    val collapse: String,
    val call: String,
    val calls: String,
    val running: String,
    val failed: String,
    val completedWithErrors: String,
    val canceled: String,
    val completed: String,
    val status: String,
    val callId: String,
    val connection: String,
    val tool: String,
    val modelTool: String,
    val timing: String,
    val error: String,
    val arguments: String,
    val result: String,
    val exportHeader: (Int) -> String,
    val startedAt: String
) {
    companion object {
        val Default = ToolTraceLabels(
            "Expand tool trace", "Collapse tool trace", "Expand", "Collapse", "tool call", "tool calls",
            "running", "failed", "completed with errors", "canceled", "completed", "Status", "Call ID",
            "Connection", "Tool", "Model tool", "Timing", "Error", "Arguments", "Result",
            { count -> "Tool calls ($count)" }, "started at"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ToolTraceBlockPreview() {
    GPTMobileTheme {
        val mockEvent = ToolEvent(
            eventId = "evt_1", runId = "run_1", callId = "call_123",
            connectionUidSnapshot = null, connectionNameSnapshot = null,
            toolName = "web_search", modelToolName = "web_search",
            arguments = "{\"query\": \"Compose preview\"}", result = "Found results for Compose preview",
            resultType = ToolEventResultType.TEXT, status = ToolEventStatus.COMPLETED, sequence = 0,
            startedAt = Instant.now().epochSecond - 5, completedAt = Instant.now().epochSecond
        )
        Box(Modifier.padding(16.dp)) { ToolTraceBlock(listOf(mockEvent)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun ToolTraceBlockRunningPreview() {
    GPTMobileTheme {
        val mockEvent = ToolEvent(
            eventId = "evt_2", runId = "run_1", callId = "call_456",
            connectionUidSnapshot = null, connectionNameSnapshot = null,
            toolName = "calculate_expression", modelToolName = "calculate_expression",
            arguments = "{\"expression\": \"2 + 2\"}", result = null, resultType = null,
            status = ToolEventStatus.RUNNING, sequence = 0, startedAt = Instant.now().epochSecond
        )
        Box(Modifier.padding(16.dp)) { ToolTraceBlock(listOf(mockEvent)) }
    }
}
