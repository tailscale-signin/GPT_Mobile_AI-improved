package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.providerInvocation
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolResultContent as AnthropicToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolUseContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.FunctionCall
import dev.chungjungsoo.gptmobile.data.dto.google.common.FunctionResponse
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role as GoogleRole
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role as OpenAIRole
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as OpenAITextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunction
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Provider history replays the original call; normalized execution arguments stay local. */
internal fun AgentToolExchange.toChatMessages(): List<ChatMessage> = listOf(
    ChatMessage(
        role = OpenAIRole.ASSISTANT,
        toolCalls = calls.map { call ->
            call.providerInvocation().let { original -> ChatToolCall(call.callId, ChatFunction(original.name, original.arguments.toString())) }
        }
    )
) + results.map { result ->
    ChatMessage(
        role = OpenAIRole.TOOL,
        content = listOf(OpenAITextContent(result.modelText())),
        toolCallId = result.callId
    )
}

internal fun AgentToolExchange.toAnthropicMessages(assistantContent: List<MessageContent>?): List<InputMessage> = listOf(
    InputMessage(
        MessageRole.ASSISTANT,
        assistantContent ?: calls.map { call -> call.providerInvocation().let { ToolUseContent(call.callId, it.name, it.arguments) } }
    ),
    InputMessage(
        MessageRole.USER,
        results.map { result ->
            AnthropicToolResultContent(result.callId, result.modelText(), result.isError)
        }
    )
)

internal fun AgentToolExchange.toGeminiContents(modelParts: List<Part>?): List<Content> {
    val callsById = calls.associateBy { it.callId }
    val originalCalls = modelParts.orEmpty().mapNotNull { it.functionCall }
    return listOf(
        Content(
            GoogleRole.MODEL,
            modelParts ?: calls.map { call -> call.providerInvocation().let { Part(functionCall = FunctionCall(call.callId, it.name, it.arguments)) } }
        ),
        Content(
            GoogleRole.USER,
            results.mapNotNull { result ->
                val call = callsById[result.callId] ?: return@mapNotNull null
                val providerCallId = if (modelParts == null) result.callId else originalCalls.getOrNull(calls.indexOf(call))?.id
                Part(
                    functionResponse = FunctionResponse(
                        id = providerCallId,
                        name = call.providerInvocation().name,
                        response = result.modelJson()
                    )
                )
            }
        )
    )
}

internal fun AgentToolResult.modelText(): String = if (assistMetadata.isNotEmpty()) {
    JsonObject(mapOf("data" to dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.element(content), "assist" to JsonArray(assistMetadata))).toString()
} else {
    when (val value = content) {
        is ToolResultContent.Text -> value.text
        is ToolResultContent.Json -> value.value.toString()
        is ToolResultContent.ResourceLinks -> value.links.joinToString("\n") { link -> link.uri }
    }.ifBlank { if (isError) "Tool failed without error details." else "Tool returned no content." }
}

private fun AgentToolResult.modelJson(): JsonObject = if (assistMetadata.isNotEmpty()) {
    JsonObject(mapOf("data" to dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.element(content), "assist" to JsonArray(assistMetadata)))
} else {
    when (val value = content) {
        is ToolResultContent.Json -> value.value.asResponseObject()

        is ToolResultContent.Text -> buildJsonObject {
            put("result", value.text)
            if (isError) put("isError", true)
        }

        is ToolResultContent.ResourceLinks -> buildJsonObject {
            put("resources", JsonArray(value.links.map { link -> JsonPrimitive(link.uri) }))
            if (isError) put("isError", true)
        }
    }
}

private fun JsonElement.asResponseObject(): JsonObject = this as? JsonObject ?: buildJsonObject { put("result", this@asResponseObject) }
