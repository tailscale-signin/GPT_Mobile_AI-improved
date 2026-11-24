package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToStream

/**
 * One capture, one frozen redactor. Retains only redacted results and opaque input digests.
 * Protected hydration and authorization still run for every source row before this cache.
 */
internal class DiagnosticRedactionReuse(
    private val redact: (JsonElement) -> JsonElement,
    private val maximumWeightBytes: Int = 512 * 1024,
    private val maximumInputCharacters: Int = 256 * 1024,
    private val maximumEntries: Int = 16
) {
    private data class Entry(val encoded: String, val weight: Long)
    private val entries = linkedMapOf<String, Entry>()
    private var retainedWeight = 0L

    data class Result(val value: JsonElement, val reused: Boolean)

    /** Row identity stays fresh; only the same payload under the same policy can be reused. */
    fun workspaceRow(value: JsonElement, authorized: () -> Unit): Result {
        val row = value as? JsonObject ?: return apply(value, authorized)
        val fields = row["fields"] as? JsonObject ?: return apply(value, authorized)
        val payload = fields["payload"] ?: return apply(value, authorized)
        if (row["table"] != JsonPrimitive("workspace_records")) return apply(value, authorized)
        authorized()
        // Keep the payload field context: a string/array payload is private even
        // when the same value would be structural evidence at another location.
        val savedPayload = apply(JsonObject(mapOf("payload" to payload)), authorized)
        val shell = redact(JsonObject(row + ("fields" to JsonObject(fields - "payload")))) as JsonObject
        val safeFields = shell["fields"] as JsonObject
        authorized()
        return Result(JsonObject(shell + ("fields" to JsonObject(safeFields + ("payload" to (savedPayload.value as JsonObject).getValue("payload"))))), savedPayload.reused)
    }

    fun apply(value: JsonElement, authorized: () -> Unit): Result {
        authorized()
        val key = if (maximumWeightBytes > 0 && maximumEntries > 0) inputDigest(value) else null
        key?.let { entries[it] }?.let { cached ->
            authorized()
            return Result(Json.parseToJsonElement(cached.encoded), true)
        }
        val result = redact(value)
        authorized()
        if (key != null) {
            // Store only encoded output, with a UTF-16 text and per-entry overhead allowance.
            // Do not retain a JSON object graph whose heap weight is implementation-dependent.
            val safeText = result.toString()
            val weight = safeText.length * 2L + 512
            if (weight <= maximumWeightBytes) {
                while (entries.isNotEmpty() && (retainedWeight + weight > maximumWeightBytes || entries.size >= maximumEntries)) {
                    retainedWeight -= entries.remove(entries.keys.first())!!.weight
                }
                entries[key] = Entry(safeText, weight)
                retainedWeight += weight
            }
        }
        return Result(result, false)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun inputDigest(value: JsonElement): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = maximumInputCharacters
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (remaining-- <= 0) throw ReuseInputLimit()
                digest.update(value.toByte())
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > remaining) throw ReuseInputLimit()
                remaining -= length
                digest.update(bytes, offset, length)
            }
        }
        return try {
            // Hash bounded UTF-8 directly; do not allocate a complete source
            // String and second byte array solely to discover a cache miss.
            Json.encodeToStream(JsonElement.serializer(), value, output)
            DiagnosticRecordChunks.hex(digest.digest())
        } catch (_: ReuseInputLimit) {
            null
        }
    }

    private class ReuseInputLimit : IOException()

    fun clear() {
        entries.clear()
        retainedWeight = 0
    }
}
