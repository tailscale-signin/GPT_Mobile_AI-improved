package dev.chungjungsoo.gptmobile.data.dto.openai.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

    @SerialName("choices")
    val choices: List<Choice>? = null,

    @SerialName("gateway_progress")
    val gatewayProgress: GatewayProgress? = null,

    @SerialName("error")
    val error: ErrorDetail? = null
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
    val checkpoint: Int? = null
)

@Serializable
data class Choice(
    @SerialName("index")
    val index: Int,

    @SerialName("delta")
    val delta: Delta,

    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Delta(
    @SerialName("role")
    val role: String? = null,

    @SerialName("content")
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
    val index: Int,
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
    val code: String? = null
)
