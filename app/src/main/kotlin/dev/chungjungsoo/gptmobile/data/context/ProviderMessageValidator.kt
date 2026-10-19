package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Validates the final provider representation; failures are deterministic and precede admission. */
internal object ProviderMessageValidator {
    const val VERSION = "provider-messages-v1"

    fun validate(payload: String, intentionalPrefill: Boolean = false) {
        val root = Json.parseToJsonElement(payload) as? JsonObject ?: invalid("INVALID_REQUEST_SHAPE")
        when {
            root.containsKey("messages") -> chat(root, intentionalPrefill)
            root.containsKey("contents") -> google(root, intentionalPrefill)
            root.containsKey("input") -> responses(root, intentionalPrefill)
            else -> invalid("INVALID_REQUEST_SHAPE")
        }
    }

    private fun chat(root: JsonObject, prefill: Boolean) {
        val messages = rows(root["messages"])
        val pending = linkedSetOf<String>()
        val seen = mutableSetOf<String>()
        var tail = ""
        for (message in messages) {
            val role = message.string("role")
            check(role in setOf("system", "developer", "user", "assistant", "tool"), "INVALID_REQUEST_ROLE")
            val content = message["content"]
            val blocks = blocks(content)
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().map { item ->
                (item as? JsonObject)?.string("id") ?: invalid("INVALID_TOOL_PAIR")
            } + blocks.filter { it.string("type") == "tool_use" }.map { it.string("id") }
            val results = if (role == "tool") listOf(message.string("tool_call_id")) else blocks.filter { it.string("type") == "tool_result" }.map { it.string("tool_use_id") }
            if (results.isEmpty()) {
                check(pending.isEmpty(), "INVALID_TOOL_PAIR")
            } else {
                check(role == "tool" || role == "user", "INVALID_TOOL_PAIR")
                results.forEach { check(it.isNotBlank() && pending.remove(it), "INVALID_TOOL_PAIR") }
            }
            if (calls.isNotEmpty()) {
                check(role == "assistant" && results.isEmpty(), "INVALID_TOOL_PAIR")
                calls.forEach { check(it.isNotBlank() && seen.add(it) && pending.add(it), "INVALID_TOOL_PAIR") }
            }
            check(content != null && content != JsonNull || calls.isNotEmpty(), "INVALID_REQUEST_CONTENT")
            check(blocks.all { it.string("type") in CHAT_PARTS }, "INVALID_REQUEST_CONTENT")
            tail = role
        }
        check(pending.isEmpty(), "INVALID_TOOL_PAIR")
        check(tail in setOf("user", "tool") || prefill && tail == "assistant", "INVALID_REQUEST_ROLE")
    }

    private fun google(root: JsonObject, prefill: Boolean) {
        val contents = rows(root["contents"])
        val pending = mutableListOf<String>()
        for (message in contents) {
            val role = message.string("role")
            check(role in setOf("user", "model"), "INVALID_REQUEST_ROLE")
            val parts = rows(message["parts"])
            val results = parts.mapNotNull { it["functionResponse"] as? JsonObject }
            if (results.isEmpty()) {
                check(pending.isEmpty(), "INVALID_TOOL_PAIR")
            } else {
                check(role == "user", "INVALID_TOOL_PAIR")
                results.forEach { check(pending.remove(it.string("name")), "INVALID_TOOL_PAIR") }
            }
            val calls = parts.mapNotNull { it["functionCall"] as? JsonObject }
            if (calls.isNotEmpty()) {
                check(role == "model", "INVALID_TOOL_PAIR")
                calls.forEach {
                    check(it.string("name").isNotBlank(), "INVALID_TOOL_PAIR")
                    pending += it.string("name")
                }
            }
            check(parts.all { part -> part.keys.any { it in GOOGLE_PARTS } }, "INVALID_REQUEST_CONTENT")
        }
        check(pending.isEmpty(), "INVALID_TOOL_PAIR")
        check(contents.last().string("role") == "user" || prefill, "INVALID_REQUEST_ROLE")
    }

    private fun responses(root: JsonObject, prefill: Boolean) {
        val input = root["input"]
        if (input is JsonPrimitive && input.isString) return
        val items = rows(input)
        val continued = root.string("previous_response_id").isNotBlank()
        val outputIds = mutableSetOf<String>()
        for (item in items) {
            if (item.string("type") == "function_call_output") {
                check(continued && item.string("call_id").isNotBlank() && outputIds.add(item.string("call_id")), "INVALID_TOOL_PAIR")
                check(item["output"] is JsonPrimitive, "INVALID_REQUEST_CONTENT")
            } else {
                check(item.string("role") in setOf("user", "assistant", "system", "developer"), "INVALID_REQUEST_ROLE")
                check(item["content"] != null && item["content"] != JsonNull, "INVALID_REQUEST_CONTENT")
                check(blocks(item["content"]).all { it.string("type") in RESPONSE_PARTS }, "INVALID_REQUEST_CONTENT")
            }
        }
        val tail = items.last()
        check(tail.string("role") == "user" || tail.string("type") == "function_call_output" || prefill && tail.string("role") == "assistant", "INVALID_REQUEST_ROLE")
    }

    private fun rows(element: JsonElement?): List<JsonObject> {
        val array = element as? JsonArray ?: invalid("INVALID_REQUEST_CONTENT")
        check(array.isNotEmpty(), "INVALID_REQUEST_CONTENT")
        return array.map { it as? JsonObject ?: invalid("INVALID_REQUEST_CONTENT") }
    }

    private fun blocks(element: JsonElement?): List<JsonObject> = when (element) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> {
            check(element.isString, "INVALID_REQUEST_CONTENT")
            emptyList()
        }
        is JsonArray -> element.map { it as? JsonObject ?: invalid("INVALID_REQUEST_CONTENT") }
        else -> invalid("INVALID_REQUEST_CONTENT")
    }
    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun check(valid: Boolean, reason: String) {
        if (!valid) invalid(reason)
    }
    private fun invalid(reason: String): Nothing = throw IllegalArgumentException("$reason: The provider request failed local message validation; no inference was sent.")
    private val CHAT_PARTS = setOf("text", "image_url", "input_audio", "file", "image", "document", "tool_use", "tool_result", "thinking", "redacted_thinking")
    private val RESPONSE_PARTS = setOf("input_text", "output_text", "input_image", "input_file", "refusal")
    private val GOOGLE_PARTS = setOf("text", "inlineData", "fileData", "inline_data", "file_data", "functionCall", "functionResponse", "executableCode", "codeExecutionResult")
}
