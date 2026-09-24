package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress

enum class GatewayWorkState {
    STARTING,
    EXPLORING,
    FOCUSED,
    ACTING,
    RECOVERING,
    SYNTHESIZING,
    FINALIZING
}

data class GatewayActivitySample(
    val sequence: Int?,
    val stage: String?,
    val message: String?,
    val toolName: String?,
    val route: String?,
    val event: String?
) {
    val identity: String
        get() = listOf(
            stage.orEmpty(),
            message.orEmpty(),
            toolName.orEmpty(),
            route.orEmpty(),
            event.orEmpty()
        ).joinToString("|")
}

internal fun gatewayEfficiencyPercent(totalToolCalls: Int?, usefulToolCalls: Int?): Int? {
    val total = totalToolCalls ?: return null
    val useful = usefulToolCalls ?: return null
    if (total <= 0) return null
    return ((useful.coerceIn(0, total) * 100.0) / total).toInt().coerceIn(0, 100)
}

internal fun resolveGatewayWorkState(
    stage: String?,
    event: String?,
    totalToolCalls: Int?,
    usefulToolCalls: Int?,
    noProgress: Int?,
    currentTool: String?
): GatewayWorkState {
    val normalizedStage = stage.orEmpty().lowercase()
    val normalizedEvent = event.orEmpty().lowercase()
    val normalized = "$normalizedStage $normalizedEvent"

    return when {
        normalizedStage.contains("final") ||
            normalizedEvent in setOf("completed", "job_completed", "final_response", "response_completed") ->
            GatewayWorkState.FINALIZING

        normalizedStage.contains("synth") ||
            normalizedStage.contains("wrap") ||
            normalizedStage.contains("summary") ||
            normalizedEvent.contains("synthesis") ->
            GatewayWorkState.SYNTHESIZING

        (noProgress ?: 0) >= 3 || normalized.contains("recover") || normalized.contains("strategy") ->
            GatewayWorkState.RECOVERING

        !currentTool.isNullOrBlank() ||
            normalizedEvent.startsWith("tool_") ||
            normalizedStage.contains("execut") ->
            GatewayWorkState.ACTING

        (totalToolCalls ?: 0) == 0 ->
            GatewayWorkState.STARTING

        gatewayEfficiencyPercent(totalToolCalls, usefulToolCalls)?.let { it >= 50 } == true ->
            GatewayWorkState.FOCUSED

        else ->
            GatewayWorkState.EXPLORING
    }
}

internal fun appendGatewayActivity(
    current: List<GatewayActivitySample>,
    progress: GatewayProgress,
    limit: Int = 6
): List<GatewayActivitySample> {
    val sample = GatewayActivitySample(
        sequence = progress.sequence,
        stage = progress.stage,
        message = progress.message,
        toolName = progress.toolName,
        route = progress.route,
        event = progress.event
    )
    val meaningful = listOf(sample.stage, sample.message, sample.toolName, sample.route, sample.event)
        .any { !it.isNullOrBlank() }
    if (!meaningful || current.lastOrNull()?.identity == sample.identity) return current
    return (current + sample).takeLast(limit.coerceAtLeast(1))
}

internal fun ActiveAgentRun.withGatewayProgress(progress: GatewayProgress): ActiveAgentRun {
    val nextStage = progress.stage?.takeIf { it.isNotBlank() } ?: gatewayStage
    val nextMessage = progress.message?.takeIf { it.isNotBlank() } ?: gatewayMessage
    val nextTotal = progress.totalToolCalls ?: gatewayToolCalls
    val nextUseful = progress.usefulToolCalls ?: gatewayUsefulToolCalls
    val nextRepository = progress.repositoryToolCalls ?: gatewayRepositoryToolCalls
    val nextNoProgress = progress.noProgress ?: gatewayNoProgress
    val nextTool = progress.toolName?.takeIf { it.isNotBlank() } ?: gatewayCurrentTool
    val nextRoute = progress.route?.takeIf { it.isNotBlank() } ?: gatewayRoute
    val nextEvent = progress.event?.takeIf { it.isNotBlank() }

    return copy(
        gatewayStage = nextStage,
        gatewayMessage = nextMessage,
        gatewayCheckpoint = progress.checkpoint ?: gatewayCheckpoint,
        gatewayRound = progress.round ?: gatewayRound,
        gatewayToolCalls = nextTotal,
        gatewayUsefulToolCalls = nextUseful,
        gatewayRepositoryToolCalls = nextRepository,
        gatewayNoProgress = nextNoProgress,
        gatewayCurrentTool = nextTool,
        gatewayRoute = nextRoute,
        gatewayResultQuality = progress.resultQuality?.takeIf { it.isNotBlank() } ?: gatewayResultQuality,
        gatewayWorkState = resolveGatewayWorkState(
            stage = nextStage,
            event = nextEvent,
            totalToolCalls = nextTotal,
            usefulToolCalls = nextUseful,
            noProgress = nextNoProgress,
            currentTool = nextTool
        ),
        gatewayActivity = appendGatewayActivity(gatewayActivity, progress)
    )
}
