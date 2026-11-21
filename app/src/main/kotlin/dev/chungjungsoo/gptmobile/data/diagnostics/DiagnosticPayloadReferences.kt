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

    suspend fun write(value: JsonElement, emit: suspend (JsonElement) -> Unit) {
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
            emit(value)
            return
        }
        val serialized = payload.toString()
        if (serialized.length < 2048) {
            emit(value)
            return
        }
        val digest = DiagnosticRecordChunks.sha256(serialized)
        val first = originals[digest]
        if (first == null || first == rowId) {
            emit(value)
            originals[digest] = rowId
        } else {
            val reference = buildJsonObject {
                put("schema", "gptmobile.workspace-payload-reference.v1")
                put("table", "workspace_records")
                put("rowId", first)
                put("pointer", "/fields/payload")
                put("redactedJsonSha256", digest)
            }
            emit(JsonObject(row + ("fields" to JsonObject(fields - "payload" + ("payload_ref" to reference)))))
        }
    }
}
