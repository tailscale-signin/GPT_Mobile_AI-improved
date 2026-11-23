package dev.chungjungsoo.gptmobile.data.diagnostics

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

    fun apply(value: JsonElement, authorized: () -> Unit): Result {
        authorized()
        val encoded = value.toString()
        val key = if (maximumWeightBytes > 0 && maximumEntries > 0 && encoded.length <= maximumInputCharacters) {
            val bytes = encoded.encodeToByteArray()
            try {
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            } finally {
                bytes.fill(0)
            }
        } else {
            null
        }
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

    fun clear() {
        entries.clear()
        retainedWeight = 0
    }
}
