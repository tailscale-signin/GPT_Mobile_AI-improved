package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * Ground direct location requests on the phone before asking a remote local model to answer.
 * The caller supplies only a resolved built-in tool name; the runner still owns execution,
 * Android permissions, tool budgets, tracing and sharing between simultaneous chat profiles.
 */
internal fun AgentProviderSession.withDeviceLocation(
    clientType: ClientType,
    userPrompt: String?,
    nativeLocationToolName: String?
): AgentProviderSession {
    if (handlesToolsInternally ||
        clientType !in setOf(ClientType.LLAMA, ClientType.OLLAMA) ||
        nativeLocationToolName == null ||
        !isDirectLocationRequest(userPrompt)
    ) {
        return this
    }
    return DeviceLocationSession(this, nativeLocationToolName)
}

private class DeviceLocationSession(
    private val delegate: AgentProviderSession,
    private val toolName: String
) : AgentProviderSession {
    private var firstRound = true

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val shouldLocate = firstRound && exchanges.isEmpty() && tools.any { it.name == toolName }
        firstRound = false
        if (shouldLocate) {
            emit(ProviderEvent.ToolCall("mobile_location_${UUID.randomUUID()}", toolName, JsonObject(emptyMap())))
            emit(ProviderEvent.Completed)
        } else {
            emitAll(delegate.streamRound(tools, exchanges))
        }
    }
}

// Deliberately match complete, explicit requests only. Descriptions, quoted examples, pasted logs,
// negations and unrelated geography questions must never cause an automatic GPS lookup.
private val directLocationRequest = Regex(
    "(?:please )?(?:where am i(?: right now)?|what(?:'s| is) my (?:current |device |phone )?location|" +
        "(?:get|find|check|show|tell me) (?:me )?my (?:current |device |phone |gps )?location|" +
        "(?:use|call|run|test) (?:the )?(?:device_location(?:\\(\\))?|(?:device )?location(?: mcp)?(?: tool)?)|" +
        "use (?:your |the )?tools to (?:find|get|check) my (?:current )?location)(?: (?:now|please))?[?.!]*",
    RegexOption.IGNORE_CASE
)

private fun isDirectLocationRequest(prompt: String?): Boolean =
    prompt != null && prompt.length <= 160 && directLocationRequest.matches(prompt.trim())
