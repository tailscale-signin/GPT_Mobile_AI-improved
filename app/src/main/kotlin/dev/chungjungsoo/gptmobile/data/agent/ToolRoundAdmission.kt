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
    var rejection: ToolBatchOversizeException? = null
        private set
    private fun reject(kind: String, observedValue: Int, allowed: Int, call: ProviderEvent.ToolCall): Boolean {
        rejection = ToolBatchOversizeException(kind, observedValue, allowed, ids.size + if (call.callId !in ids && call.callId.isNotBlank()) 1 else 0)
        return false
    }

    fun admit(call: ProviderEvent.ToolCall): Boolean {
        observed++
        if (observed > limit) return reject("COMPLETE_CALL_COUNT", observed, limit, call)
        if (call.callId.isBlank()) return reject("EMPTY_CALL_ID", 0, 1, call)
        if (call.callId.length > 256) return reject("CALL_ID_CHARACTERS", call.callId.length, 256, call)
        if (call.name.length > 256) return reject("TOOL_NAME_CHARACTERS", call.name.length, 256, call)
        if (call.callId in ids) return reject("DUPLICATE_CALL_ID", 2, 1, call)
        if ((byName[call.name] ?: 0) >= maxSameTool.coerceIn(1, 32)) return reject("SAME_TOOL_COUNT", (byName[call.name] ?: 0) + 1, maxSameTool.coerceIn(1, 32), call)
        val available = maxArgumentBytes.coerceIn(1, 64 * 1024) - argumentBytes
        val size = boundedJsonBytes(call.arguments, available)
        if (size > available) return reject("ARGUMENT_BYTES_LOWER_BOUND", argumentBytes + size, maxArgumentBytes.coerceIn(1, 64 * 1024), call)
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
internal class ToolBatchOversizeException(
    val threshold: String = "UNSPECIFIED_ADMISSION_LIMIT",
    val observedValue: Int? = null,
    val allowedValue: Int? = null,
    val completeCallIds: Int? = null
) : IllegalStateException("TOOL_BATCH_OVERSIZE: threshold=$threshold observed=${observedValue ?: "unknown"} allowed=${allowedValue ?: "unknown"} completeCallIds=${completeCallIds ?: "unknown"}; no calls in this round were dispatched.")

/** Shared streaming-parser limits, enforced before builders/maps grow. */
internal class ToolStreamGuard {
    private val slots = HashSet<String>()
    private var bytes = 0
    fun slot(key: String) {
        if (key.length > 256) throw ToolBatchOversizeException("SLOT_ID_CHARACTERS", key.length, 256)
        if (key !in slots && slots.size >= 8) throw ToolBatchOversizeException("STREAM_TOOL_SLOTS", slots.size + 1, 8)
        slots += key
    }
    fun identifier(value: String?) {
        if (value != null && value.length > 256) throw ToolBatchOversizeException("IDENTIFIER_CHARACTERS", value.length, 256)
    }
    fun append(value: String) {
        if (value.length > 16 * 1024 - bytes) throw ToolBatchOversizeException("ARGUMENT_BYTES_LOWER_BOUND", bytes + value.length, 16 * 1024)
        // Byte counting is now bounded to at most 16 KiB of UTF-16 characters.
        bytes += value.toByteArray(Charsets.UTF_8).size
        if (bytes > 16 * 1024) throw ToolBatchOversizeException("ARGUMENT_BYTES", bytes, 16 * 1024)
    }
}
