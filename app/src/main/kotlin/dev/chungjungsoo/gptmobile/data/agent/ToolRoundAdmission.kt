package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Admission happens before UI events, argument serialization, or execution. Never unlimited. */
internal class ToolRoundAdmission(
    maxCalls: Int = 8,
    private val maxArgumentBytes: Int = 16 * 1024,
    private val maxSameTool: Int = 16
) {
    private val limit = maxCalls.coerceIn(0, 64)
    private val ids = HashSet<String>()
    private val byName = HashMap<String, Int>()
    var observed: Int = 0
        private set
    var argumentBytes: Int = 0
        private set
    val admitted: Int get() = ids.size

    fun admit(call: ProviderEvent.ToolCall): Boolean {
        observed++
        if (observed > limit || call.callId.isBlank() || call.callId.length > 256 || call.name.length > 256 || call.callId in ids) return false
        if ((byName[call.name] ?: 0) >= maxSameTool.coerceIn(1, 32)) return false
        val available = maxArgumentBytes.coerceIn(1, 64 * 1024) - argumentBytes
        val size = boundedJsonBytes(call.arguments, available)
        if (size > available) return false
        ids += call.callId
        byName[call.name] = (byName[call.name] ?: 0) + 1
        argumentBytes += size
        return true
    }
}

/** Conservative encoded size; bounded walk avoids allocating serialized copies of oversized JSON. */
internal fun boundedJsonBytes(value: JsonElement, limit: Int, depth: Int = 0): Int {
    if (limit < 0 || depth > 32) return limit.coerceAtLeast(0) + 1
    var size = 2
    fun add(text: String) {
        for (character in text) {
            size += when {
                character == '"' || character == '\\' || character.code < 32 -> 6
                character.code < 128 -> 1
                character.code < 2048 -> 2
                else -> 3
            }
            if (size > limit) break
        }
    }
    when (value) {
        is JsonPrimitive -> add(value.content)
        is JsonObject -> for ((key, child) in value) {
            add(key)
            if (size > limit) break
            size += 4 + boundedJsonBytes(child, limit - size, depth + 1)
            if (size > limit) break
        }
        is JsonArray -> for (child in value) {
            size += 1 + boundedJsonBytes(child, limit - size, depth + 1)
            if (size > limit) break
        }
    }
    return size.coerceAtMost(limit + 1)
}

/** A provider protocol violation, not a transport error or a failure of any individual tool. */
internal class ToolBatchOversizeException : IllegalStateException("TOOL_BATCH_OVERSIZE: provider tool batch exceeded safe admission limits; no calls in this round were dispatched.")

/** Shared streaming-parser limits, enforced before builders/maps grow. */
internal class ToolStreamGuard {
    private val slots = HashSet<String>()
    private var bytes = 0
    fun slot(key: String) {
        if (key.length > 256 || (key !in slots && slots.size >= 8)) throw ToolBatchOversizeException()
        slots += key
    }
    fun identifier(value: String?) {
        if (value != null && value.length > 256) throw ToolBatchOversizeException()
    }
    fun append(value: String) {
        if (value.length > 16 * 1024 - bytes) throw ToolBatchOversizeException()
        // Byte counting is now bounded to at most 16 KiB of UTF-16 characters.
        bytes += value.toByteArray(Charsets.UTF_8).size
        if (bytes > 16 * 1024) throw ToolBatchOversizeException()
    }
}
