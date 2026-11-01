package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Decode a complete leading local-model control frame before the prose guard sees it. */
internal class LocalNativeToolFrameSession(private val delegate: AgentProviderSession) : AgentProviderSession by delegate {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val output = LocalPromptToolOutput()
        val nativeCalls = mutableListOf<ProviderEvent.ToolCall>()
        var completed = false
        var failed = false
        delegate.streamRound(tools, exchanges).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> output.accept(event.text).takeIf(String::isNotEmpty)?.let { emit(ProviderEvent.TextDelta(it)) }
                is ProviderEvent.ToolCall -> nativeCalls += event
                ProviderEvent.Completed -> completed = true
                is ProviderEvent.Failed -> {
                    failed = true
                    emit(event)
                }
                else -> emit(event)
            }
        }
        // Incomplete transport never authorizes a buffered command.
        if (failed || !completed) return@flow
        val buffered = output.buffered()
        // A fenced example is prose, never an executable control frame.
        val parsed = if (buffered.trimStart().startsWith("```")) PromptToolParseResult.NoCall else LocalPromptToolBridge.parseResult(buffered, tools.mapTo(hashSetOf()) { it.name })
        when (parsed) {
            is PromptToolParseResult.Call -> {
                if (nativeCalls.isNotEmpty()) {
                    emit(ProviderEvent.Failed("The provider mixed native and text tool frames. No call was dispatched.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
                    return@flow
                }
                emit(ProviderEvent.ToolCall("local-frame-${UUID.randomUUID()}", parsed.value.name, Json.parseToJsonElement(parsed.value.argumentsJson) as JsonObject))
            }
            is PromptToolParseResult.Rejected -> {
                emit(ProviderEvent.Failed("The local tool frame was incomplete or unauthorized. No call was dispatched.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
                return@flow
            }
            PromptToolParseResult.NoCall -> {
                if (buffered.isNotEmpty()) emit(ProviderEvent.TextDelta(buffered))
                nativeCalls.forEach { emit(it) }
            }
        }
        emit(ProviderEvent.Completed)
    }
}
