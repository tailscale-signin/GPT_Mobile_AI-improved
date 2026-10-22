package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import java.io.Writer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Redacts one leaf at a time; never builds a second conversation-sized JSON tree. */
internal class DebugJsonStream(private val writer: Writer, private val credentials: List<String>) {
    private var first = true
    suspend fun objectBody(block: suspend DebugJsonStream.() -> Unit) {
        writer.write("{")
        block()
        writer.write("}")
        writer.flush()
    }
    private fun name(name: String) {
        if (!first) writer.write(",")
        first = false
        writer.write(JsonPrimitive(name).toString())
        writer.write(":")
    }
    fun put(name: String, value: JsonElement) {
        name(name)
        value(value, name)
    }
    fun put(name: String, value: String?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    fun put(name: String, value: Boolean) = put(name, JsonPrimitive(value))
    fun put(name: String, value: Number) = put(name, JsonPrimitive(value))
    fun <T> array(name: String, values: Sequence<T>, encode: (T) -> JsonElement) {
        name(name)
        writer.write("[")
        var initial = true
        values.forEach {
            if (!initial) writer.write(",")
            initial = false
            value(encode(it))
        }
        writer.write("]")
    }
    suspend fun arrayFrom(name: String, produce: suspend ((JsonElement) -> Unit) -> Unit) {
        name(name)
        writer.write("[")
        var initial = true
        produce {
            if (!initial) writer.write(",")
            initial = false
            value(it)
        }
        writer.write("]")
    }
    private fun value(element: JsonElement, key: String? = null) {
        if (key != null && SENSITIVE.matches(key)) {
            writer.write("\"[redacted]\"")
            return
        }
        when (element) {
            is JsonObject -> {
                writer.write("{")
                element.entries.forEachIndexed { index, (name, child) ->
                    if (index > 0) writer.write(",")
                    writer.write(JsonPrimitive(name).toString())
                    writer.write(":")
                    value(child, name)
                }
                writer.write("}")
            }
            is JsonArray -> {
                writer.write("[")
                element.forEachIndexed { index, child ->
                    if (index > 0) writer.write(",")
                    value(child)
                }
                writer.write("]")
            }
            else -> writer.write(redactChatDebugData(element, credentials).toString())
        }
    }
    private companion object {
        val SENSITIVE = Regex("(?i)^(?:token|secretRef|protectedReference|password|secret|authorization|proxy.authorization|cookie|set.cookie|(?:x[-_])?(?:goog[-_])?api.?key|access.?token|refresh.?token|client.?secret|credential|credentials|lastShareToken)$")
    }
}
