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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventError
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
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
        toolName.contains("search", ignoreCase = true) || toolName.contains("web", ignoreCase = true)
    override val serviceNameRes = R.string.tool_name_web
    override val monogram = "W"
    override val badgeColor = Color(0xFF00897B)

    override fun getDisplayNameRes(rawName: String): Int? {
        val lower = rawName.trim().lowercase(Locale.ROOT)
        return when {
            lower == "web_search" || lower == "search" || lower.endsWith("__web_search") || lower.endsWith("__search") -> R.string.tool_name_search
            lower == "read_url" || lower == "crawl" || lower == "scrape" || lower == "fetch" || lower.endsWith("__read_url") || lower.endsWith("__crawl") -> R.string.tool_name_crawl
            else -> null
        }
    }
}

object SystemTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) =
        toolName.contains("calc", ignoreCase = true) || toolName.contains("date", ignoreCase = true) || toolName.contains("location", ignoreCase = true)
    override val serviceNameRes = R.string.tool_name_system
    override val monogram = "SYS"
    override val badgeColor = Color(0xFF5E35B1)

    override fun getDisplayNameRes(rawName: String): Int? {
        val lower = rawName.trim().lowercase(Locale.ROOT)
        return when {
            lower == "calculate_expression" || lower == "calculator" || lower == "calc" || lower.endsWith("__calculate_expression") -> R.string.tool_name_calculator
            lower == "device_location" || lower == "location" || lower.endsWith("__device_location") -> R.string.tool_name_location
            lower == "current_date" || lower == "date" || lower == "time" || lower.endsWith("__current_date") -> R.string.tool_name_date
            else -> null
        }
    }
}

object DefaultTool : ToolDefinition {
    override fun matches(toolName: String, connectionName: String?, connectionUid: String?) = true
    override val serviceNameRes = R.string.tool_name_tool
    override val monogram = "T"
    override val badgeColor = Color(0xFF546E7A)
}

val ToolRegistry = listOf(GitHubTool, BraveTool, MicrosoftTool, McpTool, WebTool, SystemTool, DefaultTool)

internal data class ToolServiceInfo(
    val serviceName: String,
    val toolDisplayName: String,
    val monogram: String,
    val badgeColor: Color,
    val textColor: Color = Color.White,
)

@Composable
internal fun resolveToolServiceInfo(
    toolName: String,
    modelToolName: String,
    connectionNameSnapshot: String? = null,
    connectionUidSnapshot: String? = null,
): ToolServiceInfo {
    val rawName = toolName.ifBlank { modelToolName }.trim()
    val toolDef = ToolRegistry.first { it.matches(rawName, connectionNameSnapshot, connectionUidSnapshot) }
    val serviceName = if (!connectionNameSnapshot.isNullOrBlank() && toolDef !is WebTool && toolDef !is SystemTool) {
        connectionNameSnapshot.trim().replaceFirstChar { it.uppercase(Locale.ROOT) }
    } else {
        stringResource(toolDef.serviceNameRes)
    }
    val monogram = if (!connectionNameSnapshot.isNullOrBlank() && toolDef == DefaultTool) {
        serviceName.take(2).uppercase(Locale.ROOT)
    } else {
        toolDef.monogram
    }
    val badgeColor = if (!connectionNameSnapshot.isNullOrBlank() && toolDef == DefaultTool) Color(0xFF455A64) else toolDef.badgeColor
    val displayNameRes = toolDef.getDisplayNameRes(rawName)
    val toolDisplayName = if (displayNameRes != null) {
        stringResource(displayNameRes)
    } else {
        val leafName = rawName.substringAfterLast("__").replace('_', ' ').replace('-', ' ').trim()
        if (leafName.isEmpty()) {
            stringResource(R.string.tool_name_tool)
        } else {
            leafName.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        }
    }
    return ToolServiceInfo(serviceName, toolDisplayName, monogram, badgeColor, toolDef.textColor)
}

@Composable
internal fun ToolServiceCircleIcon(
    info: ToolServiceInfo,
    modifier: Modifier = Modifier,
    sizeDp: Int = 24,
) {
    Box(
        modifier = modifier.size(sizeDp.dp).clip(CircleShape).background(info.badgeColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                id = if (info.monogram == GitHubTool.monogram && info.badgeColor == GitHubTool.badgeColor) {
                    R.drawable.ic_github
                } else {
                    R.drawable.ic_gpt_mobile_foreground
                },
            ),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size((sizeDp * 0.7).dp),
        )
    }
}

@Composable
internal fun ToolStatusIndicator(state: ToolCallState, modifier: Modifier = Modifier) {
    when (state) {
        is ToolCallState.Running -> CircularProgressIndicator(
            modifier = modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        is ToolCallState.Failed, is ToolCallState.Canceled -> Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Failed",
            tint = Color(0xFFD32F2F),
            modifier = modifier.size(18.dp),
        )
        is ToolCallState.Completed -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Completed",
            tint = Color(0xFF2E7D32),
            modifier = modifier.size(18.dp),
        )
    }
}

@Composable
fun ToolTraceBlock(events: List<ToolEvent>, modifier: Modifier = Modifier, contentIdentity: Any = events) {
    if (events.isEmpty()) return
    val labels = toolTraceLabels()
    var isExpanded by remember(contentIdentity) { mutableStateOf(false) }
    var query by remember(contentIdentity) { mutableStateOf("") }
    val rotationAngle by animateFloatAsState(if (isExpanded) 180f else 0f, label = "tool trace rotation")
    val firstEvent = events.first()
    val primaryServiceInfo = resolveToolServiceInfo(
        firstEvent.toolName,
        firstEvent.modelToolName,
        firstEvent.connectionNameSnapshot,
        firstEvent.connectionUidSnapshot,
    )
    val overallState = remember(events) {
        when {
            events.any { it.toToolCallState() is ToolCallState.Running } -> ToolCallState.Running
            events.any { it.toToolCallState() is ToolCallState.Failed } -> ToolCallState.Failed(true)
            events.any { it.toToolCallState() is ToolCallState.Canceled } -> ToolCallState.Canceled
            else -> ToolCallState.Completed
        }
    }
    val summary = toolTraceStatusSummary(events, labels, primaryServiceInfo, overallState)
    val searchToolTrace = stringResource(R.string.search_tool_trace)
    val noMatchingToolCalls = stringResource(R.string.no_matching_tool_calls)
    val traceBlockDescription = stringResource(R.string.tool_trace_block_content_description, summary)

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .semantics { contentDescription = traceBlockDescription },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .semantics {
                    role = Role.Button
                    contentDescription = if (isExpanded) labels.collapseToolTrace else labels.expandToolTrace
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolServiceCircleIcon(primaryServiceInfo)
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ToolStatusIndicator(overallState)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) labels.collapse else labels.expand,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotationAngle),
            )
        }
        AnimatedVisibility(isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            key(contentIdentity) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    if (events.size > 1) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(searchToolTrace) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchToolTrace },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    val filteredEvents = filterToolEvents(events, query)
                    if (filteredEvents.isEmpty()) {
                        Text(noMatchingToolCalls, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        filteredEvents.forEach { ToolTraceEventCard(it, labels) }
                    }
                }
            }
        }
    }
}

@Composable
private fun toolTraceLabels() = ToolTraceLabels(
    stringResource(R.string.tool_trace_expand_content_description),
    stringResource(R.string.tool_trace_collapse_content_description),
    stringResource(R.string.tool_trace_expand),
    stringResource(R.string.tool_trace_collapse),
    stringResource(R.string.tool_trace_call_singular),
    stringResource(R.string.tool_trace_call_plural),
    stringResource(R.string.tool_trace_status_running),
    stringResource(R.string.tool_trace_status_failed),
    stringResource(R.string.tool_trace_status_completed_with_errors),
    stringResource(R.string.tool_trace_status_canceled),
    stringResource(R.string.tool_trace_status_completed),
    stringResource(R.string.tool_trace_status),
    stringResource(R.string.tool_trace_call_id),
    stringResource(R.string.tool_trace_connection),
    stringResource(R.string.tool_trace_tool),
    stringResource(R.string.tool_trace_model_tool),
    stringResource(R.string.tool_trace_timing),
    stringResource(R.string.tool_trace_error),
    stringResource(R.string.tool_trace_arguments),
    stringResource(R.string.tool_trace_result),
    ToolTraceLabels.Default.exportHeader,
    stringResource(R.string.tool_trace_timing_started_at),
)

@Composable
private fun ToolTraceEventCard(event: ToolEvent, labels: ToolTraceLabels) {
    val callDescription = stringResource(R.string.tool_trace_call_content_description, event.callId, event.status.lowercase(Locale.ROOT))
    val serviceInfo = resolveToolServiceInfo(event.toolName, event.modelToolName, event.connectionNameSnapshot, event.connectionUidSnapshot)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = callDescription },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ToolServiceCircleIcon(serviceInfo, sizeDp = 20)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${event.sequence + 1}. ${serviceInfo.serviceName} — ${serviceInfo.toolDisplayName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                ToolStatusIndicator(event.toToolCallState())
            }
            Spacer(Modifier.height(4.dp))
            if (event.modelToolName != event.toolName) ToolTraceLine(labels.modelTool, event.modelToolName)
            ToolTraceLine(labels.status, event.status)
            ToolTraceLine(labels.callId, event.callId)
            connectionLabel(event)?.let { ToolTraceLine(labels.connection, it) }
            toolTimingLabel(event, labels)?.let { ToolTraceLine(labels.timing, it) }
            event.error?.takeIf { it.isNotBlank() }?.let { ToolTraceLine(labels.error, toolEventErrorText(it)) }
            ToolTraceBlockText(labels.arguments, event.arguments)
            event.result?.takeIf { it.isNotBlank() }?.let { ToolTraceBlockText(labels.result, it) }
        }
    }
}

@Composable
private fun ToolTraceLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ToolTraceBlockText(label: String, value: String) {
    Text("$label:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    Text(boundedText(value), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
}

internal fun filterToolEvents(events: List<ToolEvent>, query: String): List<ToolEvent> {
    val ordered = events.sortedBy { it.sequence }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isEmpty()) return ordered
    return ordered.filter { event ->
        listOfNotNull(
            event.connectionUidSnapshot,
            event.connectionNameSnapshot,
            event.toolName,
            event.modelToolName,
            event.status,
            event.callId,
            event.arguments,
            event.result,
            event.error,
            timingLabel(event, ToolTraceLabels.Default),
        ).any { normalizedQuery in it.lowercase(Locale.ROOT) }
    }
}

internal fun toolTraceStatusSummary(
    events: List<ToolEvent>,
    labels: ToolTraceLabels = ToolTraceLabels.Default,
    primaryServiceInfo: ToolServiceInfo? = null,
    overallState: ToolCallState = ToolCallState.Completed,
): String {
    val count = events.size
    if (events.isEmpty()) return "0 ${labels.calls}"
    val distinctToolNames = events.map { it.toolName.ifBlank { it.modelToolName } }.distinct()
    return if (distinctToolNames.size == 1 && primaryServiceInfo != null) {
        "${primaryServiceInfo.serviceName} — ${primaryServiceInfo.toolDisplayName}"
    } else {
        val noun = if (count == 1) labels.call else labels.calls
        "$count $noun"
    }
}

internal fun formatToolDuration(event: ToolEvent): String? {
    val seconds = toolDurationSeconds(event) ?: return null
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
            event.result?.takeIf { it.isNotBlank() }?.let { appendIndentedBlock(labels.result, it) }
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
    val startedAt: String,
) {
    companion object {
        val Default = ToolTraceLabels(
            "Expand tool trace", "Collapse tool trace", "Expand", "Collapse", "tool call", "tool calls",
            "running", "failed", "completed with errors", "canceled", "completed", "Status", "Call ID",
            "Connection", "Tool", "Model tool", "Timing", "Error", "Arguments", "Result",
            { count -> "Tool calls ($count)" }, "started at",
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
            startedAt = Instant.now().epochSecond - 5, completedAt = Instant.now().epochSecond,
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
            status = ToolEventStatus.RUNNING, sequence = 0, startedAt = Instant.now().epochSecond,
        )
        Box(Modifier.padding(16.dp)) { ToolTraceBlock(listOf(mockEvent)) }
    }
}
