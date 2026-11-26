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

    private data class BodyOrigin(val rowId: Long, val pointer: String)
    private val bodyOriginals = object : LinkedHashMap<String, BodyOrigin>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BodyOrigin>?) = size > 2048
    }
    private data class BodyProjection(val value: JsonObject, val originals: List<Pair<String, BodyOrigin>>, val references: Int)

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
            val bodies = if (payload is JsonObject && "payload_body_refs" !in fields) {
                timings?.measure(DiagnosticCaptureTimings.Phase.PAYLOAD_DEDUPLICATION) { projectBodies(row, fields, payload, rowId) }
                    ?: projectBodies(row, fields, payload, rowId)
            } else {
                BodyProjection(row, emptyList(), 0)
            }
            emit(bodies.value, serialized.takeIf { bodies.references == 0 })
            // Register only after the complete logical row has been written.
            // Whole-payload references never point at a body-compacted row.
            if (bodies.references == 0) originals[digest] = rowId
            bodies.originals.forEach { (key, origin) -> bodyOriginals[key] = origin }
            timings?.bodyReferenceReused(bodies.references)
        } else {
            emit(JsonObject(row + ("fields" to JsonObject(fields - "payload" + ("payload_ref" to reference(first, "/fields/payload", digest))))), null)
        }
    }

    private fun projectBodies(row: JsonObject, fields: JsonObject, payload: JsonObject, rowId: Long): BodyProjection {
        val references = linkedMapOf<String, JsonElement>()
        val retained = mutableListOf<Pair<String, BodyOrigin>>()
        for ((key, value) in payload) {
            if (key !in DiagnosticPayloadBodies.fields) continue
            val digest = DiagnosticRecordChunks.largePayloadDigest(value, 2048) ?: continue
            val first = bodyOriginals[digest]
            if (first == null || first.rowId == rowId) {
                val escaped = key.replace("~", "~0").replace("/", "~1")
                retained += digest to BodyOrigin(rowId, "/fields/payload/$escaped")
            } else {
                references[key] = reference(first.rowId, first.pointer, digest)
            }
        }
        val result = if (references.isEmpty()) {
            row
        } else {
            val compactFields = fields + mapOf(
                "payload" to JsonObject(payload - references.keys),
                "payload_body_refs" to JsonObject(references)
            )
            JsonObject(row + ("fields" to JsonObject(compactFields)))
        }
        return BodyProjection(result, retained, references.size)
    }

    private fun reference(rowId: Long, pointer: String, digest: String) = buildJsonObject {
        put("schema", "gptmobile.workspace-payload-reference.v1")
        put("table", "workspace_records")
        put("rowId", rowId)
        put("pointer", pointer)
        put("redactedJsonSha256", digest)
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
