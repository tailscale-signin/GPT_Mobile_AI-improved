package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Text-only Free routes use the same authorized AgentRunner tools, budgets and approval gates. */
internal class FreePromptToolSession(
    private val open: suspend (String, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val names = tools.map { it.name }.toSet()
        val instruction = LocalPromptToolBridge.instruction(tools).replaceFirst("Authorized local tools", "Authorized app tools")
        val output = LocalPromptToolOutput()
        var complete = false
        var failed = false
        open(instruction, exchanges).streamRound(emptyList(), emptyList()).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> output.accept(event.text).takeIf(String::isNotEmpty)?.let { emit(ProviderEvent.TextDelta(it)) }
                ProviderEvent.Completed -> complete = true
                is ProviderEvent.Failed -> {
                    failed = true
                    emit(event)
                }
                is ProviderEvent.ToolCall -> {
                    failed = true
                    emit(ProviderEvent.Failed("The text-only route returned an unexpected native tool call.", errorCode = "OUTPUT_PROTOCOL_INVALID"))
                }
                else -> emit(event)
            }
        }
        output.progressClosure().takeIf(String::isNotEmpty)?.let { emit(ProviderEvent.TextDelta(it)) }
        // An interrupted or rate-limited generation cannot execute a buffered command.
        // Keep physical request accounting unchanged: invalid calls fail without hidden retries.
        if (failed) return@flow
        if (!complete) {
            emit(ProviderEvent.Failed("The provider stopped before completing its tool decision.", errorCode = "OUTPUT_PROTOCOL_INVALID"))
            return@flow
        }
        when (val parsed = LocalPromptToolBridge.parseResult(output.buffered(), names, allowBareCalls = true)) {
            is PromptToolParseResult.Call -> {
                val arguments = Json.parseToJsonElement(parsed.value.argumentsJson) as JsonObject
                emit(ProviderEvent.ToolCall(UUID.randomUUID().toString(), parsed.value.name, arguments))
                emit(ProviderEvent.Completed)
            }
            PromptToolParseResult.NoCall -> {
                output.buffered().takeIf(String::isNotEmpty)?.let { emit(ProviderEvent.TextDelta(it)) }
                emit(ProviderEvent.Completed)
            }
            is PromptToolParseResult.Rejected -> emit(ProviderEvent.Failed("The provider did not return a valid authorized tool call.", errorCode = "OUTPUT_PROTOCOL_INVALID"))
        }
    }
}
