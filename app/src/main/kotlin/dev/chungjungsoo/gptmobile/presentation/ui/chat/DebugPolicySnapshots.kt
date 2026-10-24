package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Export-only normalization. Durable run snapshots retain their current recovery format. */
internal class DebugPolicySnapshots(private val redact: (JsonElement) -> JsonElement = { it }) {
    private val entries = linkedMapOf<String, JsonElement>()

    fun normalize(record: JsonElement): JsonElement = visit(redact(record))

    fun table(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("digestRepresentation", "redacted-canonical-json-utf8")
        put("entries", JsonObject(entries))
    }

    private fun visit(value: JsonElement, key: String? = null): JsonElement {
        if (value is JsonPrimitive && value.isString && key in setOf("synthesisSnapshot", "acceptedPolicyJson")) {
            val parsed = runCatching { Json.parseToJsonElement(value.content) as? JsonObject }.getOrNull() ?: return value
            return JsonPrimitive(visit(parsed).toString())
        }
        val normalized = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (name, child) -> visit(child, name) })
            is JsonArray -> JsonArray(value.map { visit(it) })
            else -> value
        }
        if (normalized !is JsonObject) return normalized
        val kind = when {
            key in setOf("requested", "requestedPolicy") && "requestedPolicyDigest" in normalized && "participantUids" in normalized -> "COMBINED_REQUESTED_POLICY"
            key == "plan" && "baselineByOwner" in normalized && "effectivePlanDigest" in normalized -> "COMBINED_WORK_PLAN"
            else -> return normalized
        }
        val payload = canonical(normalized)
        val id = debugBodyDigest("$kind\n$payload")
        entries.putIfAbsent(
            id,
            buildJsonObject {
                put("kind", kind)
                put("payload", payload)
            }
        )
        return buildJsonObject { put("policySnapshotRef", id) }
    }

    companion object {
        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }

        /** Resolves both legacy inline policy nodes and versioned references for offline readers. */
        fun resolve(value: JsonElement, table: JsonObject? = null): JsonElement {
            val entries = table?.get("entries") as? JsonObject ?: JsonObject(emptyMap())
            if (table != null) require((table["schemaVersion"] as? JsonPrimitive)?.content == "1") { "UNSUPPORTED_POLICY_SNAPSHOT_SCHEMA" }
            fun expand(element: JsonElement, path: Set<String>): JsonElement = when (element) {
                is JsonObject -> {
                    val id = (element["policySnapshotRef"] as? JsonPrimitive)?.content
                    if (id == null) {
                        JsonObject(element.mapValues { expand(it.value, path) })
                    } else {
                        require(element.size == 1 && path.size < 32 && id !in path) { "CORRUPT_POLICY_SNAPSHOT_REFERENCE" }
                        val entry = entries[id] as? JsonObject ?: error("MISSING_POLICY_SNAPSHOT:$id")
                        val kind = (entry["kind"] as? JsonPrimitive)?.content ?: error("CORRUPT_POLICY_SNAPSHOT_KIND")
                        val payload = entry["payload"] ?: error("CORRUPT_POLICY_SNAPSHOT_PAYLOAD")
                        require(debugBodyDigest("$kind\n${canonical(payload)}") == id) { "CORRUPT_POLICY_SNAPSHOT_DIGEST" }
                        expand(payload, path + id)
                    }
                }
                is JsonArray -> JsonArray(element.map { expand(it, path) })
                else -> element
            }
            return expand(value, emptySet())
        }

        fun resolveSnapshot(encoded: String, table: JsonObject? = null): JsonObject =
            resolve(Json.parseToJsonElement(encoded), table) as? JsonObject ?: error("CORRUPT_POLICY_SNAPSHOT")
    }
}
