package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Local response header observation or a bound saved-job receipt.
 * An empty observation clears any earlier response's gateway provenance.
 */
@Serializable
data class GatewayResponseMetadata(
    val jobId: String? = null,
    val requestId: String? = null,
    val version: String? = null,
    val progressProtocol: String? = null,
    val singleflightRole: String? = null,
    val terminalStatus: String? = null,
    val resultQuality: String? = null,
    val instanceId: String? = null,
    val gatewayId: String? = null,
    val principalId: String? = null,
    val conversationId: String? = null,
    val commandId: String? = null,
    val detachSafe: Boolean = false,
    /** Set by the local HTTP/recovery transport, never decoded from a provider body. */
    @Transient val transportVerified: Boolean = false
)

/** Generic provider request IDs and profile names cannot establish a gateway connection. */
internal fun gatewayResponseMetadata(header: (String) -> String?): GatewayResponseMetadata? {
    fun observed(name: String): String? = header(name)?.trim()?.takeIf {
        it.length in 1..256 && it.none { character -> character.isISOControl() }
    }
    val jobId = observed("X-Gateway-Job-ID")
    val requestId = observed("X-Gateway-Request-ID")
    val version = observed("X-Gateway-Version")
    val instanceId = observed("X-Gateway-Instance-ID")
    if (jobId == null && requestId == null && version == null && instanceId == null) return null
    return GatewayResponseMetadata(
        jobId = jobId,
        requestId = requestId,
        version = version,
        progressProtocol = observed("X-Gateway-Progress-Protocol"),
        singleflightRole = observed("X-Gateway-Singleflight"),
        instanceId = instanceId,
        gatewayId = observed("X-Gateway-Id"),
        principalId = observed("X-Gateway-Principal-ID"),
        conversationId = observed("X-Gateway-Conversation-Id"),
        commandId = observed("X-Gateway-Command-Id"),
        detachSafe = observed("X-Gateway-Detach-Safe") == "true",
        transportVerified = true
    )
}

internal fun GatewayResponseMetadata.observedResponseSource(profileUid: String, modelId: String): ResponseSource = ResponseSource(
    transport = if (transportVerified) ResponseTransport.GATEWAY else ResponseTransport.UNKNOWN,
    profileUid = profileUid,
    modelId = modelId,
    gatewayVerified = transportVerified
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
    val gatewayVersion: String? = null,
    val contractVersion: Int? = null,
    val instanceId: String? = null,
    val auth: GatewayAuthCapabilities? = null,
    val identityScope: String? = null,
    val desktop: GatewayDesktopCapabilities? = null,
    val memory: GatewayMemoryCapabilities? = null,
    val contracts: Map<String, List<String>> = emptyMap(),
    val features: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    @SerialName("accepted_request_headers")
    val acceptedRequestHeaders: List<String> = emptyList(),
    val build: kotlinx.serialization.json.JsonObject? = null
) {
    val supportsKeyFreeConnection: Boolean
        get() = contractVersion == 1 &&
            gatewayVersion?.matches(Regex("(14|15)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) == true &&
            auth?.apiKeyRequired == false &&
            auth.mode in setOf("loopback", "trusted_proxy", "open_network")

    fun supportsContract(name: String, version: String): Boolean = contracts[name]?.contains(version) == true

    // Older gateway routes also placed descriptive strings/numbers in this map.
    // Only a literal JSON boolean grants a feature; metadata is never coerced.
    fun supportsFeature(name: String): Boolean = features[name] == kotlinx.serialization.json.JsonPrimitive(true)
}

@Serializable
data class GatewayDesktopCapabilities(
    val managed: Boolean = false,
    val maintenanceProtocol: Int = 0,
    val stateSchema: Int = 0,
    val updateAuthority: String? = null,
    val idleUpdates: Boolean = false
)

@Serializable
data class GatewayAuthCapabilities(
    val mode: String,
    val apiKeyRequired: Boolean = true,
    val legacyBearerEnabled: Boolean = false
)

@Serializable
data class GatewayMemoryCapabilities(
    val authority: String,
    val protocolVersion: Int = 0,
    val workspaceAdapterEnabled: Boolean = false
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
    @SerialName("structured_error")
    val structuredError: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("completed_at")
    @Serializable(with = GatewayEpochSecondsSerializer::class)
    val completedAt: Long? = null,
    val result: dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk? = null,
    val job: GatewaySavedJob? = null
)

@Serializable
data class GatewaySavedJob(
    val model: String? = null,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("execution_isolation_schema") val executionIsolationSchema: Int? = null,
    @SerialName("execution_isolation_scope") val executionIsolationScope: String? = null,
    @SerialName("execution_isolation") val executionIsolation: String? = null,
    @SerialName("server_tools_allowed") val serverToolsAllowed: Boolean? = null,
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("pending_client_tool_call_ids") val pendingClientToolCallIds: List<String> = emptyList()
)

/**
 * Gateway job cancellation response from POST /gateway/jobs/{jobId}/cancel.
 */
@Serializable
data class GatewayCancelResult(
    @SerialName("job_id")
    val jobId: String,
    val canceled: Boolean,
    val status: String? = null,
    val message: String? = null,
    val cancellationRequested: Boolean = false,
    val cancellationOutcome: String? = null
) {
    val outcome: GatewayCancellationOutcome
        get() = when ((cancellationOutcome ?: status)?.lowercase()) {
            "confirmed", "cancelled", "canceled" -> GatewayCancellationOutcome.CONFIRMED
            "requested", "cancellation_requested", "cancel_requested" -> GatewayCancellationOutcome.REQUESTED
            "not_canceled", "completed", "failed", "interrupted" -> GatewayCancellationOutcome.NOT_CANCELED
            else -> GatewayCancellationOutcome.UNKNOWN
        }
}

enum class GatewayCancellationOutcome { REQUESTED, CONFIRMED, NOT_CANCELED, UNKNOWN }

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

    val generation: String? = null,

    val sequence: Int? = null,
    val event: String? = null,
    val replayGap: Boolean? = null,
    val generationChanged: Boolean? = null,
    val previousGeneration: String? = null,
    val resultAvailable: Boolean? = null,
    val recovery: String? = null,
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

    @SerialName("tool_result")
    val toolResult: String? = null,

    @SerialName("result_quality")
    val resultQuality: String? = null,

    val prefetch: Boolean? = null,

    @SerialName("workflow_profile")
    val workflowProfile: String? = null,

    @SerialName("full_tool_count")
    val fullToolCount: Int? = null,

    @SerialName("selected_tool_count")
    val selectedToolCount: Int? = null,

    @SerialName("recovery_attempt")
    val recoveryAttempt: Int? = null,

    @SerialName("empty_count")
    val emptyCount: Int? = null,

    @SerialName("consecutive_failures")
    val consecutiveFailures: Int? = null,

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
