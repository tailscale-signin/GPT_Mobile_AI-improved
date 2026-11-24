package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Scope each reference to an earlier complete, redacted row in this same stage and archive part. */
internal class DiagnosticPayloadReferences {
    private val originals = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 4096
    }

    suspend fun write(value: JsonElement, timings: DiagnosticCaptureTimings? = null, emit: suspend (JsonElement) -> Unit) {
        prepare(value, timings, retainEncoding = false) { output, _ -> emit(output) }
    }

    /** Reuse the payload encoding used by its digest instead of encoding it again in the row. */
    suspend fun writeEncoded(value: JsonElement, timings: DiagnosticCaptureTimings? = null, emit: suspend (String) -> Unit) {
        prepare(value, timings, retainEncoding = true) { output, encodedPayload ->
            val encode = { if (encodedPayload == null) output.toString() else encodeRow(output as JsonObject, encodedPayload) }
            val encoded = timings?.measure(DiagnosticCaptureTimings.Phase.SERIALIZATION, encode) ?: encode()
            emit(encoded)
        }
    }

    private suspend fun prepare(value: JsonElement, timings: DiagnosticCaptureTimings?, retainEncoding: Boolean, emit: suspend (JsonElement, String?) -> Unit) {
        val row = value as? JsonObject
        val fields = row?.get("fields") as? JsonObject
        val payload = fields?.get("payload")
        val rowId = (row?.get("rowId") as? JsonPrimitive)?.longOrNull
        if (row?.get("table") != JsonPrimitive("workspace_records") ||
            rowId == null ||
            fields == null ||
            payload == null ||
            (payload is JsonPrimitive && payload.content.length < 2048)
        ) {
            emit(value, null)
            return
        }
        val serialized = if (retainEncoding) {
            timings?.measure(DiagnosticCaptureTimings.Phase.SERIALIZATION) { payload.toString() } ?: payload.toString()
        } else {
            null
        }
        val digest = if (serialized != null) {
            serialized.takeIf { it.length >= 2048 }?.let(DiagnosticRecordChunks::sha256)
        } else {
            DiagnosticRecordChunks.largePayloadDigest(payload, 2048)
        }
        if (digest == null) {
            emit(value, serialized)
            return
        }
        val first = originals[digest]
        if (first == null || first == rowId) {
            emit(value, serialized)
            originals[digest] = rowId
        } else {
            val reference = buildJsonObject {
                put("schema", "gptmobile.workspace-payload-reference.v1")
                put("table", "workspace_records")
                put("rowId", first)
                put("pointer", "/fields/payload")
                put("redactedJsonSha256", digest)
            }
            emit(JsonObject(row + ("fields" to JsonObject(fields - "payload" + ("payload_ref" to reference)))), null)
        }
    }

    private fun encodeRow(row: JsonObject, payload: String): String = buildString(payload.length + 512) {
        appendObject(row) { name, value ->
            if (name == "fields") {
                appendObject(value as JsonObject) { field, content ->
                    if (field == "payload") append(payload) else append(content)
                }
            } else {
                append(value)
            }
        }
    }

    private inline fun StringBuilder.appendObject(value: JsonObject, field: (String, JsonElement) -> Unit) {
        append('{')
        value.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(JsonPrimitive(entry.key))
            append(':')
            field(entry.key, entry.value)
        }
        append('}')
    }
}
