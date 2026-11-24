package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Provider bytes stay internal until the invocation's marker restorer authorizes publication. */
internal sealed interface PrivateProviderFrame {
    data class Delivery(val mode: PrivateDeliveryMode) : PrivateProviderFrame
    data class Answer(val text: String) : PrivateProviderFrame
    data class Reasoning(val text: String) : PrivateProviderFrame
    data class Usage(val input: Int?, val output: Int?, val total: Int?, val finishReason: String?, val final: Boolean = false) : PrivateProviderFrame
    data object Completed : PrivateProviderFrame
}

internal enum class PrivateResponseFailure {
    HTTP_STATUS,
    NETWORK,
    RESPONSE_LIMIT,
    MALFORMED_EVENT,
    UNEXPECTED_CONTROL,
    PROVIDER_ERROR,
    MISSING_COMPLETION,
    MISSING_ANSWER
}

/** Intentionally has no body, URL, provider message, or cause that could contain protected data. */
internal class PrivateProviderResponseException(val reason: PrivateResponseFailure, val httpStatus: Int? = null) : IOException("The verified private provider response could not be accepted (${reason.name}).")

/** Local admission failures must not be attributed to provider availability or retried. */
internal class PrivateDispatchPolicyChanged : IOException("The private request authority changed before transmission or publication.")

/** Narrow stateless text protocol. Unknown control events fail closed; no transport retries. */
internal class PrivateProviderResponse(private val kind: ClientType, private val publish: (PrivateProviderFrame) -> Unit) {
    private var input: Int? = null
    private var output: Int? = null
    private var total: Int? = null
    private var finishReason: String? = null
    private var terminal = false
    private var answered = false

    fun event(data: String): Boolean {
        if (terminal) fail(PrivateResponseFailure.MALFORMED_EVENT)
        if (data.trim() == "[DONE]") {
            if (kind == ClientType.ANTHROPIC) fail(PrivateResponseFailure.MALFORMED_EVENT)
            terminal = true
        } else {
            val value = parse(data)
            if (value["error"] != null && value["error"] != JsonNull) fail(PrivateResponseFailure.PROVIDER_ERROR)
            if (kind == ClientType.ANTHROPIC) anthropic(value) else compatible(value)
        }
        return terminal
    }

    fun completedJson(data: String) {
        val value = parse(data)
        if (value["error"] != null && value["error"] != JsonNull) fail(PrivateResponseFailure.PROVIDER_ERROR)
        when (kind) {
            ClientType.LLAMA, ClientType.OLLAMA -> answer(string(value, "text") ?: fail(PrivateResponseFailure.MISSING_ANSWER))
            ClientType.ANTHROPIC -> {
                val content = value["content"] as? JsonArray ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
                finish(value["stop_reason"])
                checkedAnthropicBlocks(content).forEach(::anthropicBlock)
            }
            else -> {
                val choice = choice(value) ?: fail(PrivateResponseFailure.MISSING_ANSWER)
                val message = choice["message"] as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
                rejectTools(message)
                finish(choice["finish_reason"])
                val content = string(message, "content") ?: fail(PrivateResponseFailure.MISSING_ANSWER)
                val thought = string(message, "reasoning_content")
                answer(content)
                thought?.let(::reasoning)
            }
        }
        usage(value["usage"] as? JsonObject)
        terminal = true
        end()
    }

    fun end() {
        // An explicit finish reason can terminate compatible providers that close without [DONE].
        if (!terminal && (kind == ClientType.ANTHROPIC || finishReason !in FINISH_REASONS)) fail(PrivateResponseFailure.MISSING_COMPLETION)
        if (!answered) fail(PrivateResponseFailure.MISSING_ANSWER)
        publish(PrivateProviderFrame.Usage(input, output, total, finishReason, final = true))
        publish(PrivateProviderFrame.Completed)
    }

    private fun compatible(value: JsonObject) {
        val choice = choice(value)
        if (choice == null) {
            usage(value["usage"] as? JsonObject)
            return
        }
        val delta = choice["delta"] as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
        rejectTools(delta)
        finish(choice["finish_reason"])
        val thought = string(delta, "reasoning_content")
        val alternateThought = string(delta, "reasoning")
        val content = string(delta, "content")
        usage(value["usage"] as? JsonObject)
        thought?.let(::reasoning)
        alternateThought?.let(::reasoning)
        content?.let(::answer)
    }

    private fun anthropic(value: JsonObject) {
        when (string(value, "type")) {
            "message_start" -> {
                val message = value["message"] as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
                val content = (message["content"] as? JsonArray)?.let(::checkedAnthropicBlocks)
                usage(message["usage"] as? JsonObject)
                content?.forEach(::anthropicBlock)
            }
            "content_block_start" -> anthropicBlock(value["content_block"] as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT))
            "content_block_delta" -> {
                val delta = value["delta"] as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
                when (string(delta, "type")) {
                    "text_delta" -> answer(string(delta, "text") ?: fail(PrivateResponseFailure.MALFORMED_EVENT))
                    "thinking_delta" -> reasoning(string(delta, "thinking") ?: fail(PrivateResponseFailure.MALFORMED_EVENT))
                    "signature_delta", "citations_delta" -> Unit
                    "input_json_delta" -> fail(PrivateResponseFailure.UNEXPECTED_CONTROL)
                    else -> fail(PrivateResponseFailure.MALFORMED_EVENT)
                }
            }
            "message_delta" -> {
                val delta = value["delta"] as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
                finish(delta["stop_reason"])
                usage(value["usage"] as? JsonObject)
            }
            "message_stop" -> terminal = true
            "ping", "content_block_stop" -> Unit
            "error" -> fail(PrivateResponseFailure.PROVIDER_ERROR)
            else -> fail(PrivateResponseFailure.MALFORMED_EVENT)
        }
    }

    private fun anthropicBlock(block: JsonObject) {
        when (string(block, "type")) {
            "text" -> string(block, "text")?.let(::answer)
            "thinking" -> string(block, "thinking")?.let(::reasoning)
            "redacted_thinking" -> Unit
            "tool_use", "server_tool_use", "tool_result" -> fail(PrivateResponseFailure.UNEXPECTED_CONTROL)
            else -> fail(PrivateResponseFailure.MALFORMED_EVENT)
        }
    }

    /** A buffered response/event may contain several blocks; none can publish ahead of a later native control. */
    private fun checkedAnthropicBlocks(content: JsonArray): List<JsonObject> = content.map { it as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT) }.onEach { block ->
        when (string(block, "type")) {
            "text" -> string(block, "text")
            "thinking" -> string(block, "thinking")
            "redacted_thinking" -> Unit
            "tool_use", "server_tool_use", "tool_result" -> fail(PrivateResponseFailure.UNEXPECTED_CONTROL)
            else -> fail(PrivateResponseFailure.MALFORMED_EVENT)
        }
    }

    private fun choice(value: JsonObject): JsonObject? {
        val choices = value["choices"] as? JsonArray ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
        if (choices.isEmpty()) return null // The final usage-only event has no choices.
        val result = choices.singleOrNull() as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
        val index = result["index"]
        if (index != null && index != JsonPrimitive(0)) fail(PrivateResponseFailure.MALFORMED_EVENT)
        return result
    }

    private fun rejectTools(value: JsonObject) {
        string(value, "role")?.let { if (it != "assistant") fail(PrivateResponseFailure.UNEXPECTED_CONTROL) }
        listOf("tool_calls", "function_call").forEach { name ->
            val control = value[name]
            if (control != null && control != JsonNull && control != JsonArray(emptyList())) fail(PrivateResponseFailure.UNEXPECTED_CONTROL)
        }
    }

    private fun finish(value: kotlinx.serialization.json.JsonElement?) {
        if (value == null || value == JsonNull) return
        val reason = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
        if (reason in setOf("tool_calls", "function_call", "tool_use")) fail(PrivateResponseFailure.UNEXPECTED_CONTROL)
        if (reason == "error") fail(PrivateResponseFailure.PROVIDER_ERROR)
        finishReason = reason.takeIf { it in FINISH_REASONS } ?: "unknown"
    }

    private fun usage(value: JsonObject?) {
        if (value == null) return
        fun count(vararg names: String) = names.firstNotNullOfOrNull { name ->
            (value[name] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
        }
        input = count("prompt_tokens", "input_tokens") ?: input
        output = count("completion_tokens", "output_tokens") ?: output
        total = count("total_tokens") ?: total
        publish(PrivateProviderFrame.Usage(input, output, total, finishReason))
    }

    private fun answer(text: String) {
        if (text.isEmpty()) return
        answered = true
        publish(PrivateProviderFrame.Answer(text))
    }

    private fun reasoning(text: String) {
        if (text.isNotEmpty()) publish(PrivateProviderFrame.Reasoning(text))
    }

    private fun string(value: JsonObject, field: String): String? {
        val item = value[field] ?: return null
        if (item == JsonNull) return null
        return (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
    }

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: fail(PrivateResponseFailure.MALFORMED_EVENT)
    } catch (_: IllegalArgumentException) {
        fail(PrivateResponseFailure.MALFORMED_EVENT)
    }

    private fun fail(reason: PrivateResponseFailure): Nothing = throw PrivateProviderResponseException(reason)

    companion object {
        private val FINISH_REASONS = setOf("stop", "length", "max_tokens", "content_filter", "refusal", "end_turn", "stop_sequence", "pause_turn", "model_context_window_exceeded")
    }
}
