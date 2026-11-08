package dev.chungjungsoo.gptmobile.data.dto.openai.response

import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatCompletionChunk(
    @SerialName("id")
    val id: String? = null,

    @SerialName("object")
    val objectType: String? = null,

    @SerialName("created")
    val created: Long? = null,

    @SerialName("model")
    val model: String? = null,
    val provider: String? = null,
    @Transient val cacheStatus: String? = null,

    @SerialName("choices")
    val choices: List<Choice>? = null,

    @SerialName("gateway_progress")
    val gatewayProgress: GatewayProgress? = null,

    // Only response headers or a bound saved-job lookup can supply transport provenance.
    @Transient
    val gatewayMetadata: GatewayResponseMetadata? = null,

    @SerialName("error")
    val error: ErrorDetail? = null,

    @SerialName("usage")
    val usage: ChatCompletionUsage? = null,

    @SerialName("timings")
    val timings: ChatCompletionTimings? = null,
    @SerialName("gateway_timing")
    val gatewayTiming: GatewayGenerationTiming? = null,
    @SerialName("gateway_provenance")
    val gatewayProvenance: GatewayGenerationProvenance? = null,

    @SerialName("gateway_delivery")
    val gatewayDeliveryTiming: GatewayDeliveryTiming? = null,

    @kotlinx.serialization.Transient
    val providerResponseTiming: dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming? = null,

    // Transport-owned signal: only an explicit SSE [DONE], never an arbitrary socket EOF.
    @Transient
    val streamFinished: Boolean = false,
    @Transient val retryDurationMs: Long? = null,
    @Transient val retryAttempts: Int? = null,
    @Transient val applicationNotice: String? = null,
    @Transient val serializedOutputTokens: Int? = null,
    @Transient val observedFinishReason: String? = null,
    @Transient val reasoningControlReceipt: dev.chungjungsoo.gptmobile.data.network.ReasoningControlReceipt? = null
)

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,

    @SerialName("completion_tokens")
    val completionTokens: Int? = null,

    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    val cost: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("cost_details") val costDetails: JsonObject? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: JsonObject? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: JsonObject? = null
)

@Serializable
data class GatewayProgressUi(
    @SerialName("icon")
    val icon: String? = null,

    @SerialName("title")
    val title: String? = null,

    @SerialName("show_origin_text")
    val showOriginText: Boolean? = null,

    @SerialName("show_server_text")
    val showServerText: Boolean? = null,

    @SerialName("show_mcp_badge")
    val showMcpBadge: Boolean? = null
)

@Serializable
data class GatewayProgress(
    @SerialName("protocol")
    val protocol: String? = null,

    @SerialName("origin")
    val origin: String? = null,

    @SerialName("event")
    val event: String? = null,

    @SerialName("job_id")
    val jobId: String? = null,

    @SerialName("sequence")
    val sequence: Int? = null,

    @SerialName("phase")
    val phase: String? = null,

    @SerialName("stage")
    val stage: String? = null,

    @SerialName("message")
    val message: String? = null,

    @SerialName("timestamp")
    val timestamp: Double? = null,

    @SerialName("status")
    val status: String? = null,

    @SerialName("tool_call_id")
    val toolCallId: String? = null,

    @SerialName("tool_name")
    val toolName: String? = null,

    @SerialName("display_title")
    val displayTitle: String? = null,

    @SerialName("ui")
    val ui: GatewayProgressUi? = null,

    @SerialName("tool_source")
    val toolSource: String? = null,

    @SerialName("server")
    val server: String? = null,

    @SerialName("route")
    val route: String? = null,

    @SerialName("tool_args")
    val toolArgs: JsonObject? = null,

    @SerialName("tool_result")
    val toolResult: String? = null,

    @SerialName("result_quality")
    val resultQuality: String? = null,

    @SerialName("duration_ms")
    val durationMs: Long? = null,

    @SerialName("round")
    val round: Int? = null,

    @SerialName("total_tool_calls")
    val totalToolCalls: Int? = null,

    @SerialName("useful_tool_calls")
    val usefulToolCalls: Int? = null,

    @SerialName("repository_tool_calls")
    val repositoryToolCalls: Int? = null,

    @SerialName("no_progress")
    val noProgress: Int? = null,

    @SerialName("checkpoint")
    val checkpoint: Int? = null,

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
    val consecutiveFailures: Int? = null

)

@Serializable
data class Choice(
    @SerialName("index")
    val index: Int = 0,

    @SerialName("delta")
    val delta: Delta = Delta(),

    @SerialName("finish_reason")
    val finishReason: String? = null,

    @SerialName("message")
    val message: Delta? = null
) {
    // Complete chat messages omit the streaming-only tool-call index.
    val effectiveDelta: Delta
        get() = message?.let { complete ->
            complete.copy(toolCalls = complete.toolCalls?.mapIndexed { index, call -> call.copy(index = index) })
        } ?: delta
}

@Serializable
data class Delta(
    @SerialName("role")
    val role: String? = null,

    @SerialName("content")
    @Serializable(with = OpenAITextContentSerializer::class)
    val content: String? = null,

    @SerialName("reasoning")
    val reasoning: String? = null,

    @SerialName("reasoning_content")
    val reasoningContent: String? = null,

    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCallDelta>? = null
) {
    val effectiveReasoning: String?
        get() = reasoning ?: reasoningContent
}

@Serializable
data class ChatToolCallDelta(
    @SerialName("index")
    val index: Int = 0,
    @SerialName("id")
    val id: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("function")
    val function: ChatFunctionDelta? = null
)

@Serializable
data class ChatFunctionDelta(
    @SerialName("name")
    val name: String? = null,
    @SerialName("arguments")
    val arguments: String? = null
)

@Serializable
data class ErrorDetail(
    @SerialName("message")
    val message: String,

    @SerialName("type")
    val type: String? = null,

    @SerialName("code")
    val code: String? = null,

    @kotlinx.serialization.Transient
    val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null,

    @SerialName("retryable")
    val retryable: Boolean? = null,

    @kotlinx.serialization.Transient
    val failureDetails: kotlinx.serialization.json.JsonObject? = null
)

/** llama.cpp timing data survives a gateway's buffered SSE response. */
@Serializable
data class ChatCompletionTimings(
    @SerialName("predicted_per_second")
    val predictedPerSecond: Double? = null,
    @SerialName("predicted_n")
    val predictedTokens: Int? = null,
    @SerialName("predicted_ms")
    val predictedMs: Double? = null,
    @SerialName("prompt_n")
    val promptTokens: Int? = null,
    @SerialName("prompt_ms")
    val promptMs: Double? = null,
    @SerialName("queue_ms")
    val queueMs: Double? = null
) {
    val decodeTokensPerSecond: Double?
        get() = predictedPerSecond?.takeIf { it.isFinite() && it > 0 }
            ?: predictedMs?.takeIf { it.isFinite() && it > 0 }?.let { ms ->
                predictedTokens?.takeIf { it > 0 }?.let { it * 1000.0 / ms }
            }
}

/** Backend observation is separate from first text actually published by Android. */
@Serializable
data class GatewayGenerationTiming(
    @SerialName("measurement_scope") val measurementScope: String? = null,
    @SerialName("backend_first_output_ms") val firstOutputMs: Double? = null,
    @SerialName("backend_first_visible_output_ms") val firstVisibleOutputMs: Double? = null,
    @SerialName("backend_first_reasoning_output_ms") val firstReasoningOutputMs: Double? = null,
    @SerialName("backend_completion_ms") val completionMs: Double? = null,
    @SerialName("backend_response_headers_ms") val responseHeadersMs: Double? = null,
    @SerialName("backend_first_event_ms") val firstEventMs: Double? = null,
    @SerialName("delivery_mode") val deliveryMode: String? = null
) {
    fun observedFields(): Map<String, Double> = if (measurementScope != "physical_backend_request") {
        emptyMap()
    } else {
        mapOf(
            "backend_first_output_ms" to firstOutputMs,
            "backend_first_visible_output_ms" to firstVisibleOutputMs,
            "backend_first_reasoning_output_ms" to firstReasoningOutputMs,
            "backend_completion_ms" to completionMs,
            "backend_response_headers_ms" to responseHeadersMs,
            "backend_first_event_ms" to firstEventMs
        ).mapNotNull { (name, value) -> value?.takeIf { it.isFinite() && it in 0.0..86_400_000.0 }?.let { name to it } }.toMap()
    }
}
