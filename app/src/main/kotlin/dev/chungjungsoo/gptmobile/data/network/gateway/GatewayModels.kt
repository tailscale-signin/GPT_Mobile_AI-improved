package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gateway response header metadata.
 * Captured from HTTP response headers on /chat/completions requests.
 */
@Serializable
data class GatewayResponseMetadata(
    val jobId: String? = null,
    val requestId: String? = null,
    val version: String? = null,
    val progressProtocol: String? = null,
    val singleflightRole: String? = null,
    val performanceProfile: String? = null
)

/**
 * Gateway capabilities response from GET /gateway/capabilities.
 */
@Serializable
data class GatewayCapabilities(
    val version: String? = null,
    @SerialName("supports_resume")
    val supportsResume: Boolean = false,
    @SerialName("supports_cancellation")
    val supportsCancellation: Boolean = false,
    @SerialName("supports_result_recovery")
    val supportsResultRecovery: Boolean = false,
    @SerialName("progress_protocol_version")
    val progressProtocolVersion: String? = null,
    val protocols: List<String> = emptyList(),
    @SerialName("performance_profiles")
    val performanceProfiles: List<String> = emptyList()
)

/**
 * Gateway job result response from GET /gateway/jobs/{jobId}/result.
 */
@Serializable
data class GatewayJobResult(
    @SerialName("job_id")
    val jobId: String,
    val status: String,
    val content: String? = null,
    @SerialName("result_quality")
    val resultQuality: String? = null,
    val error: String? = null,
    @SerialName("completed_at")
    val completedAt: Long? = null
)

/**
 * Gateway job cancellation response from POST /gateway/jobs/{jobId}/cancel.
 */
@Serializable
data class GatewayCancelResult(
    @SerialName("job_id")
    val jobId: String,
    val canceled: Boolean,
    val message: String? = null
)

/**
 * Gateway progress event from SSE stream.
 */
@Serializable
data class GatewayProgress(
    @SerialName("protocol")
    val protocol: String? = null,

    @SerialName("job_id")
    val jobId: String? = null,

    @SerialName("request_id")
    val requestId: String? = null,

    val sequence: Int? = null,
    val event: String? = null,
    val status: String? = null,
    val stage: String? = null,
    val message: String? = null,

    @SerialName("tool_call_id")
    val toolCallId: String? = null,

    @SerialName("tool_name")
    val toolName: String? = null,

    @SerialName("tool_source")
    val toolSource: String? = null,

    val server: String? = null,
    val route: String? = null,

    @SerialName("result_quality")
    val resultQuality: String? = null,

    val prefetch: Boolean? = null,
    val ui: GatewayProgressUi? = null
)

/**
 * UI configuration for progress events.
 */
@Serializable
data class GatewayProgressUi(
    val icon: String? = null,
    val title: String? = null,

    @SerialName("show_origin_text")
    val showOriginText: Boolean? = null,

    @SerialName("show_server_text")
    val showServerText: Boolean? = null,

    @SerialName("show_mcp_badge")
    val showMcpBadge: Boolean? = null
)
