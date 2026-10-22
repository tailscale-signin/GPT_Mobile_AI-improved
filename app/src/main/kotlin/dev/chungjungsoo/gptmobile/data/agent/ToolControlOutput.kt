package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Detection only: assistant text can never grant permission to execute a tool. */
internal object ToolControlOutput {
    // Mentioning source code or a JSON API is not a request to display control frames.
    fun requestedJson(text: String): Boolean = listOf(
        Regex("""(?i)\b(?:respond|reply|return|output|answer|format|write|provide|show|give|generate|produce|convert|serialize)\b[^.!?\n]{0,80}\b(?:json|schema|code example|code sample|tool.call example)\b"""),
        Regex("""(?i)\b(?:json|schema|code)\s+(?:example|sample|format|only|output|response)\b"""),
        Regex("""(?i)\b(?:explain|analyze|debug|review)\b[^.!?\n]{0,40}\b(?:this|following)\s+(?:json|code|schema)\b""")
    ).any { it.containsMatchIn(text) }
    fun isControl(text: String): Boolean {
        val obj = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return false
        fun name(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,160}")) == true
        if (obj.keys.all { it in setOf("name", "parameters") } && name(obj["name"]) && obj["parameters"] is JsonObject) return true
        if (obj.keys.all { it in setOf("name", "arguments") } && name(obj["name"]) && (obj["arguments"] is JsonObject || obj["arguments"] is JsonPrimitive)) return true
        val calls = obj["tool_calls"] as? JsonArray
        return (obj["role"] as? JsonPrimitive)?.content == "assistant" &&
            !calls.isNullOrEmpty() &&
            obj.keys.all { it in setOf("role", "content", "reasoning", "reasoning_content", "tool_calls", "name") } &&
            calls.all { call ->
                val record = call as? JsonObject
                val function = record?.get("function") as? JsonObject
                name(function?.get("name") ?: record?.get("name"))
            }
    }
}
