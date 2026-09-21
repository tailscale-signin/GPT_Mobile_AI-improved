package dev.chungjungsoo.gptmobile.data.dto.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured gateway progress payload streamed via SSE / JSON chunks.
 * Sent by Gateway v7.1/v7.2+ to report workflow phase and tool status.
 */
@Serializable
data class GatewayProgress(
    @SerialName("event")
    val event: String? = null,

    @SerialName("job_id")
    val jobId: String? = null,

    @SerialName("tool_call_id")
    val toolCallId: String? = null,

    @SerialName("tool_name")
    val toolName: String? = null,

    @SerialName("tool")
    val tool: String? = null,

    @SerialName("tool_source")
    val toolSource: String? = null, // "gateway", "client", "remote_fallback"

    @SerialName("route")
    val route: String? = null, // "gateway_mcp", "client_tool", etc.

    @SerialName("server")
    val server: String? = null,

    @SerialName("status")
    val status: String? = null, // "queued", "running", "completed", "retrying", "remote_fallback", "no_useful_result", "blocked_duplicate", "failed", "memory_checkpoint"

    @SerialName("result_quality")
    val resultQuality: String? = null, // "useful", "no_useful_result", etc.

    @SerialName("duration_ms")
    val durationMs: Long? = null,

    @SerialName("round")
    val round: Int? = null,

    @SerialName("phase")
    val phase: String? = null, // "preparing", "research", "review", "synthesis", "finalizing", "tool"

    @SerialName("message")
    val message: String? = null,

    @SerialName("total_tool_calls")
    val totalToolCalls: Int? = null,

    @SerialName("tool_calls")
    val toolCallsCount: Int? = null,

    @SerialName("useful_tool_calls")
    val usefulToolCalls: Int? = null,

    @SerialName("useful_calls")
    val usefulCallsCount: Int? = null,

    @SerialName("repository_tool_calls")
    val repositoryToolCalls: Int? = null,

    @SerialName("checkpoint")
    val checkpoint: Int? = null
) {
    val effectiveToolName: String?
        get() = toolName ?: tool

    val effectiveTotalToolCalls: Int?
        get() = totalToolCalls ?: toolCallsCount

    val effectiveUsefulToolCalls: Int?
        get() = usefulToolCalls ?: usefulCallsCount

    val effectiveSource: GatewayToolSource
        get() = when {
            toolSource.equals("gateway", ignoreCase = true) || route.equals("gateway_mcp", ignoreCase = true) -> GatewayToolSource.GATEWAY
            toolSource.equals("remote_fallback", ignoreCase = true) -> GatewayToolSource.REMOTE_FALLBACK
            toolSource.equals("client", ignoreCase = true) || route.equals("client_tool", ignoreCase = true) -> GatewayToolSource.CLIENT
            else -> GatewayToolSource.GATEWAY
        }
}

enum class GatewayToolSource(val label: String, val badgeColor: Long) {
    CLIENT("CLIENT", 0xFF00897B),
    GATEWAY("GATEWAY", 0xFF00838F),
    REMOTE_FALLBACK("REMOTE FALLBACK", 0xFFFB542B)
}

enum class GatewayBubbleState(val symbol: String, val label: String) {
    QUEUED("○", "Queued"),
    RUNNING("●", "Running"),
    COMPLETED("✓", "Completed"),
    RETRYING("↻", "Retrying"),
    REMOTE_FALLBACK("⇄", "Remote fallback"),
    NO_USEFUL_RESULT("⚠", "No useful result"),
    BLOCKED_DUPLICATE("⊘", "Blocked duplicate"),
    FAILED("✕", "Failed"),
    MEMORY_CHECKPOINT("💾", "Memory checkpoint");

    companion object {
        fun fromStatus(status: String?, resultQuality: String? = null): GatewayBubbleState {
            if (resultQuality.equals("no_useful_result", ignoreCase = true)) return NO_USEFUL_RESULT
            if (resultQuality.equals("useful", ignoreCase = true)) return COMPLETED

            return when (status?.lowercase()) {
                "queued" -> QUEUED
                "running" -> RUNNING
                "completed", "success" -> COMPLETED
                "retrying", "retry" -> RETRYING
                "remote_fallback", "fallback" -> REMOTE_FALLBACK
                "no_useful_result" -> NO_USEFUL_RESULT
                "blocked_duplicate", "duplicate" -> BLOCKED_DUPLICATE
                "failed", "error" -> FAILED
                "memory_checkpoint", "checkpoint" -> MEMORY_CHECKPOINT
                else -> RUNNING
            }
        }
    }
}
