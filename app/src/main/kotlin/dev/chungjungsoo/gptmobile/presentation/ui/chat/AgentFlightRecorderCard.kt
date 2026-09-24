package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.GatewayActivitySample
import dev.chungjungsoo.gptmobile.data.agent.GatewayWorkState
import dev.chungjungsoo.gptmobile.data.agent.gatewayEfficiencyPercent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase


@Composable
internal fun CompactAgentActivityBar(
    run: ActiveAgentRun?,
    modifier: Modifier = Modifier,
    overrideText: String? = null
) {
    val inferredText = when {
        run == null ->
            stringResource(R.string.agent_live_preparing)

        run.phase == LocalInferencePhase.PREFILL ->
            stringResource(R.string.agent_live_prefill)

        run.phase == LocalInferencePhase.GENERATING && run.gatewayStage.isNullOrBlank() ->
            stringResource(R.string.agent_live_generating)

        run.gatewayWorkState == GatewayWorkState.STARTING &&
            (!run.gatewayStage.isNullOrBlank() || !run.gatewayMessage.isNullOrBlank()) ->
            friendlyGatewayActivity(run.gatewayStage, run.gatewayMessage, run.gatewayCurrentTool)

        run.gatewayWorkState == GatewayWorkState.STARTING ->
            stringResource(R.string.agent_live_preparing)

        run.gatewayWorkState == GatewayWorkState.EXPLORING ->
            friendlyGatewayActivity(run.gatewayStage, run.gatewayMessage, run.gatewayCurrentTool)

        run.gatewayWorkState == GatewayWorkState.FOCUSED ->
            stringResource(R.string.agent_live_focused)

        run.gatewayWorkState == GatewayWorkState.ACTING ->
            friendlyGatewayActivity(run.gatewayStage, run.gatewayMessage, run.gatewayCurrentTool)

        run.gatewayWorkState == GatewayWorkState.RECOVERING ->
            stringResource(R.string.agent_live_recovering)

        run.gatewayWorkState == GatewayWorkState.SYNTHESIZING ->
            stringResource(R.string.agent_live_synthesizing)

        else ->
            stringResource(R.string.agent_live_finalizing)
    }

    val liveText = overrideText?.takeIf(String::isNotBlank) ?: inferredText

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(9.dp))
                AnimatedContent(
                    targetState = liveText,
                    transitionSpec = {
                        fadeIn(tween(280)) togetherWith fadeOut(tween(180))
                    },
                    label = "agentLiveActivity",
                    modifier = Modifier.weight(1f)
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 37.dp, top = 6.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(99.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

@Composable
private fun friendlyGatewayActivity(
    stage: String?,
    message: String?,
    toolName: String?
): String {
    val signal = listOf(stage, message, toolName)
        .filterNotNull()
        .joinToString(" ")
        .lowercase()
    return when {
        "memory" in signal || "context" in signal ->
            stringResource(R.string.agent_live_memory)
        "github" in signal || "repository" in signal || "repo_" in signal ->
            stringResource(R.string.agent_live_repository)
        "search" in signal || "find" in signal ->
            stringResource(R.string.agent_live_searching)
        "file" in signal || "read" in signal ->
            stringResource(R.string.agent_live_files)
        "tool" in signal || !toolName.isNullOrBlank() ->
            stringResource(R.string.agent_live_acting)
        "reason" in signal || "plan" in signal ->
            stringResource(R.string.agent_live_planning)
        else ->
            stringResource(R.string.agent_live_exploring)
    }
}

@Composable
internal fun AgentFlightRecorderCard(
    run: ActiveAgentRun,
    toolEvents: List<ToolEvent> = emptyList(),
    modifier: Modifier = Modifier
) {
    var expanded by remember(run.runId) { mutableStateOf(false) }
    val completedTools = toolEvents.count { it.status == ToolEventStatus.COMPLETED && !it.isError }
    val failedTools = toolEvents.count { it.status == ToolEventStatus.FAILED || it.isError }
    val runningTool = toolEvents.lastOrNull {
        it.status == ToolEventStatus.RUNNING || it.status == ToolEventStatus.PENDING
    }
    val totalToolCalls = run.gatewayToolCalls ?: toolEvents.size.takeIf { it > 0 }
    val usefulToolCalls = run.gatewayUsefulToolCalls ?: completedTools.takeIf { toolEvents.isNotEmpty() }
    val efficiency = gatewayEfficiencyPercent(totalToolCalls, usefulToolCalls)
    val stateLabel = gatewayWorkStateLabel(run.gatewayWorkState)
    val stage = run.gatewayStage?.takeIf(String::isNotBlank)
    val summary = when {
        !run.gatewayMessage.isNullOrBlank() -> run.gatewayMessage!!
        runningTool != null -> "Running ${runningTool.modelToolName.ifBlank { runningTool.toolName }}…"
        stage != null -> stage
        else -> stateLabel
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_flight_recorder_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$stateLabel · $summary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                efficiency?.let {
                    Text(
                        text = stringResource(R.string.agent_metric_efficiency, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.tool_trace_collapse_content_description
                        else R.string.tool_trace_expand_content_description
                    ),
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgentMetricRow(
                        run = run,
                        totalToolCalls = totalToolCalls,
                        usefulToolCalls = usefulToolCalls,
                        fallbackFailures = failedTools
                    )
                    run.gatewayWorkflowProfile?.takeIf(String::isNotBlank)?.let { profile ->
                        val selected = run.gatewaySelectedToolCount
                        val full = run.gatewayFullToolCount
                        Text(
                            text = if (selected != null && full != null && full > 0) {
                                stringResource(R.string.agent_workflow_surface, profile, selected, full)
                            } else {
                                stringResource(R.string.agent_workflow_profile, profile)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    run.gatewayCurrentTool?.takeIf(String::isNotBlank)?.let { tool ->
                        Text(
                            text = stringResource(
                                R.string.agent_current_tool,
                                tool,
                                run.gatewayRoute?.takeIf(String::isNotBlank) ?: stringResource(R.string.agent_route_local)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (run.gatewayWorkState == GatewayWorkState.RECOVERING) {
                        Text(
                            text = stringResource(R.string.agent_recovering_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (run.gatewayActivity.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_recent_activity),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        run.gatewayActivity.takeLast(3).reversed().forEach { sample ->
                            AgentActivityLine(sample)
                        }
                    }
                    if (toolEvents.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_recent_tools),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        toolEvents.takeLast(4).reversed().forEach { event ->
                            AgentToolActivityLine(event)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentMetricRow(
    run: ActiveAgentRun,
    totalToolCalls: Int?,
    usefulToolCalls: Int?,
    fallbackFailures: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AgentMetric(
            label = stringResource(R.string.agent_metric_round_short),
            value = run.gatewayRound?.toString() ?: "—",
            modifier = Modifier.weight(1f)
        )
        AgentMetric(
            label = stringResource(R.string.agent_metric_tools_short),
            value = totalToolCalls?.toString() ?: "—",
            modifier = Modifier.weight(1f)
        )
        AgentMetric(
            label = stringResource(R.string.agent_metric_useful_short),
            value = usefulToolCalls?.toString() ?: "—",
            modifier = Modifier.weight(1f)
        )
        AgentMetric(
            label = stringResource(R.string.agent_metric_stall_short),
            value = (run.gatewayNoProgress ?: fallbackFailures.takeIf { it > 0 })?.toString() ?: "—",
            modifier = Modifier.weight(1f)
        )
    }
    run.gatewayCheckpoint?.let { checkpoint ->
        Text(
            text = stringResource(R.string.agent_metric_checkpoint, checkpoint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AgentMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun AgentActivityLine(sample: GatewayActivitySample) {
    val title = sample.toolName?.takeIf(String::isNotBlank)
        ?: sample.stage?.takeIf(String::isNotBlank)
        ?: sample.event?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.agent_activity_update)
    val detail = sample.message?.takeIf(String::isNotBlank)
        ?: sample.route?.takeIf(String::isNotBlank)
        ?: ""
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AgentToolActivityLine(event: ToolEvent) {
    val info = resolveToolServiceInfo(
        toolName = event.toolName,
        modelToolName = event.modelToolName,
        connectionNameSnapshot = event.connectionNameSnapshot,
        connectionUidSnapshot = event.connectionUidSnapshot
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolServiceCircleIcon(info, sizeDp = 20)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.toolDisplayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (info.serviceName.isNotBlank()) {
                Text(
                    text = info.serviceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        ToolStatusIndicator(event.toToolCallState())
    }
}

@Composable
private fun gatewayWorkStateLabel(state: GatewayWorkState): String = stringResource(
    when (state) {
        GatewayWorkState.STARTING -> R.string.agent_state_starting
        GatewayWorkState.EXPLORING -> R.string.agent_state_exploring
        GatewayWorkState.FOCUSED -> R.string.agent_state_focused
        GatewayWorkState.ACTING -> R.string.agent_state_acting
        GatewayWorkState.RECOVERING -> R.string.agent_state_recovering
        GatewayWorkState.SYNTHESIZING -> R.string.agent_state_synthesizing
        GatewayWorkState.FINALIZING -> R.string.agent_state_finalizing
    }
)
