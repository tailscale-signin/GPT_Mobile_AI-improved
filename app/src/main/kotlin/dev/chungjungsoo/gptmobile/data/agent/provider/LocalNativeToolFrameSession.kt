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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        // Some local templates emit the operation's base name. Resolve it only
        // when exactly one advertised capability owns that name in this round.
        val aliases = tools.groupBy { it.name.substringBefore("__") }
            .filter { (name, matches) -> matches.size == 1 && matches.single().name != name && tools.none { it.name == name } }
            .mapValues { it.value.single() }
        val parseTools = tools + aliases.map { (name, tool) -> tool.copy(name = name) }
        // A fenced example is prose, never an executable control frame.
        val parsed = when {
            buffered.trimStart().startsWith("```") -> PromptToolParseResult.NoCall
            buffered.trimStart().startsWith("<tool_call>") && buffered.substringAfter("<tool_call>").trimStart().startsWith("<function=") -> parseQwenFrame(buffered, parseTools)
            else -> LocalPromptToolBridge.parseResult(buffered, parseTools.mapTo(hashSetOf()) { it.name })
        }
        when (parsed) {
            is PromptToolParseResult.Call -> {
                if (nativeCalls.isNotEmpty()) {
                    emit(frameFailure("mixed_frames", buffered, tools))
                    return@flow
                }
                val name = aliases[parsed.value.name]?.name ?: parsed.value.name
                emit(ProviderEvent.ToolCall("local-frame-${UUID.randomUUID()}", name, Json.parseToJsonElement(parsed.value.argumentsJson) as JsonObject))
            }
            is PromptToolParseResult.Rejected -> {
                emit(frameFailure(parsed.reason, buffered, tools))
                return@flow
            }
            PromptToolParseResult.NoCall -> {
                if (buffered.isNotEmpty()) emit(ProviderEvent.TextDelta(buffered))
                nativeCalls.forEach { emit(it) }
            }
        }
        emit(ProviderEvent.Completed)
    }

    // Qwen's published chat template uses function/parameter tags, including raw strings:
    // https://huggingface.co/Qwen/Qwen3.5-9B/blob/21eca8a083a2121a92fba681f4a7c72cf20ff1a7/tokenizer_config.json
    // Match one complete control frame; never search prose or decode general XML/entities.
    private fun parseQwenFrame(text: String, tools: List<AgentToolDefinition>): PromptToolParseResult {
        fun rejected() = PromptToolParseResult.Rejected("malformed_arguments")
        if (text.length > 65_536) return rejected()
        if (!text.trimEnd().endsWith("</tool_call>")) return PromptToolParseResult.Rejected("incomplete_frame")
        val frame = Regex("(?s)^<tool_call>\\s*<function=([A-Za-z0-9_.-]{1,128})>(.*)</function>\\s*</tool_call>$").matchEntire(text.trim()) ?: return PromptToolParseResult.Rejected("malformed_call")
        val name = frame.groupValues[1]
        val tool = tools.singleOrNull { it.name == name } ?: return PromptToolParseResult.Rejected(if (tools.isEmpty()) "tools_disabled" else "tool_unavailable")
        val properties = tool.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        var remaining = frame.groupValues[2].trim()
        val values = linkedMapOf<String, JsonElement>()
        while (remaining.isNotEmpty()) {
            if (values.size >= 64) return rejected()
            val parameter = Regex("^<parameter=([A-Za-z0-9_.-]{1,128})>").find(remaining) ?: return rejected()
            val key = parameter.groupValues[1]
            if (key in values) return rejected()
            val schema = properties[key] as? JsonObject ?: return rejected()
            val end = remaining.indexOf("</parameter>", parameter.value.length)
            if (end < 0) return rejected()
            val raw = remaining.substring(parameter.value.length, end)
                .let { if (it.startsWith("\r\n")) it.drop(2) else it.removePrefix("\n") }
                .let { if (it.endsWith("\r\n")) it.dropLast(2) else it.removeSuffix("\n") }
            if (listOf("<tool_call", "</tool_call", "<function=", "</function", "<parameter=").any(raw::contains)) return rejected()
            val type = (schema["type"] as? JsonPrimitive)?.content
            val value = when (type) {
                "string" -> JsonPrimitive(raw)
                "boolean" -> when (raw.trim().lowercase()) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> return rejected()
                }
                "integer", "number", "array", "object", "null" -> runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return rejected()
                else -> return rejected()
            }
            values[key] = value
            remaining = remaining.substring(end + "</parameter>".length).trim()
        }
        // The runner still validates the full schema and dispatch-time permissions.
        return PromptToolParseResult.Call(PromptToolCall(name, JsonObject(values).toString()))
    }

    private fun frameFailure(reason: String, text: String, tools: List<AgentToolDefinition>): ProviderEvent.Failed {
        val tagged = text.trimStart().startsWith("<tool_call>")
        val complete = !tagged || text.trimEnd().endsWith("</tool_call>")
        val code = when {
            !complete || reason == "incomplete_frame" -> "INCOMPLETE_FRAME"
            reason == "tools_disabled" -> "TOOLS_DISABLED"
            reason == "tool_unavailable" -> "UNKNOWN_TOOL"
            reason == "mixed_frames" -> "MIXED_FRAMES"
            reason == "ambiguous_call" -> "AMBIGUOUS_FRAME"
            reason == "malformed_arguments" -> "INVALID_ARGUMENTS"
            else -> "MALFORMED_FRAME"
        }
        // Never echo the control payload, arguments, or a model-invented name into diagnostics.
        return ProviderEvent.Failed(
            "Local tool frame rejected: ${code.lowercase().replace('_', ' ')}. No call was dispatched.",
            errorCode = "TOOL_PROTOCOL_$code",
            failureDetails = buildJsonObject {
                put(
                    "format",
                    if (tagged && text.substringAfter("<tool_call>").trimStart().startsWith("<function=")) {
                        "qwen_tags"
                    } else if (tagged) {
                        "tagged_json"
                    } else {
                        "other_control"
                    }
                )
                put("completeness", if (tagged) if (complete) "closed" else "incomplete" else "unverified")
                put(
                    "tool_name_match",
                    when (reason) {
                        "tool_unavailable" -> "no_match"
                        "tools_disabled" -> "not_applicable"
                        "malformed_arguments", "mixed_frames" -> "unverified"
                        else -> "unverified"
                    }
                )
                put("authorization", if (tools.isEmpty()) "disabled" else "advertised_tools_only")
                put("dispatched", false)
            }
        )
    }
}
