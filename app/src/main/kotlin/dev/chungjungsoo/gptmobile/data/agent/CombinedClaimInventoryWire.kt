package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Lossless prompt encoding; complete propositions and their qualified origins stay readable. */
internal object CombinedClaimInventoryWire {
    private val aliasPattern = Regex("C[1-9][0-9]*\\.[1-9][0-9]*")
    private val defaults = listOf(JsonObject(emptyMap()), JsonPrimitive("UNVERIFIED"), JsonArray(emptyList()))
    val schema: JsonObject = buildJsonObject {
        put("version", 1)
        put("columns", JsonArray(listOf("id", "text", "origins", "qualifiers", "evidence_status", "supporting_evidence_ids").map(::JsonPrimitive)))
        put(
            "defaults",
            buildJsonObject {
                put("qualifiers", defaults[0])
                put("evidence_status", defaults[1])
                put("supporting_evidence_ids", defaults[2])
            }
        )
    }

    const val INSTRUCTION = "Rows follow claim_inventory_schema; trailing default columns are omitted. An origin is [alias,start,end], or [start,end] when alias equals row ID. A missing range is [] or [alias]. Alias prefix before the dot identifies its contributor; offsets are UTF-16, start inclusive/end exclusive in that immutable response. Choose IDs by full text, never position. Conflict IDs reference these complete claims and qualifiers."

    fun encode(claim: CombinedClaimRecord, evidenceStatus: String, supportingEvidenceIds: List<String>): JsonArray {
        require(aliasPattern.matches(claim.id) && claim.proposition.isNotBlank()) { "Invalid Combined claim identity" }
        require(claim.occurrences.isNotEmpty() && claim.id in claim.candidateIds) { "Combined claim has no canonical origin" }
        require(claim.candidateIds.size == claim.occurrences.size) { "Combined claim repeats an origin alias" }
        require(evidenceStatus.isNotBlank() && supportingEvidenceIds.all(String::isNotBlank)) { "Invalid Combined evidence metadata" }
        val origins = claim.occurrences.map { occurrence ->
            require(aliasPattern.matches(occurrence.candidateId)) { "Invalid Combined origin alias" }
            JsonArray(
                buildList {
                    if (occurrence.candidateId != claim.id) add(JsonPrimitive(occurrence.candidateId))
                    occurrence.sourceSpan?.let { span ->
                        require(span.startUtf16 >= 0 && span.endUtf16 > span.startUtf16) { "Invalid Combined origin range" }
                        add(JsonPrimitive(span.startUtf16))
                        add(JsonPrimitive(span.endUtf16))
                    }
                }
            )
        }
        val fields = mutableListOf<JsonElement>(
            JsonPrimitive(claim.id),
            JsonPrimitive(claim.proposition),
            JsonArray(origins),
            Json.encodeToJsonElement(CombinedClaimQualifiers.serializer(), claim.qualifiers),
            JsonPrimitive(evidenceStatus),
            JsonArray(supportingEvidenceIds.distinct().map(::JsonPrimitive))
        )
        while (fields.size > 3 && fields.last() == defaults[fields.lastIndex - 3]) fields.removeAt(fields.lastIndex)
        return JsonArray(fields)
    }

    /** Legacy text envelopes remain readable; a declared row schema cannot hide behind parse failure. */
    fun parseEnvelope(envelope: String): JsonObject? {
        val parsed = runCatching { Json.parseToJsonElement(envelope) }.getOrNull()
        val root = parsed as? JsonObject
        val declaresSchema = envelope.contains("\"claim_inventory_schema\"") || parsed?.toString()?.contains("\"claim_inventory_schema\"") == true
        require(root != null || !declaresSchema) { "Invalid declared Combined claim inventory envelope" }
        return root
    }

    fun validatePrompt(prompt: String) {
        decode(parseEnvelope(prompt))
    }

    /** Old frozen prompts retain object rows. Declared row formats must never degrade to an empty inventory. */
    fun decode(envelope: JsonObject?): List<JsonObject> {
        if (envelope == null) return emptyList()
        val declared = envelope["claim_inventory_schema"]
        val inventory = envelope["claim_inventory"] ?: run {
            require(declared == null) { "Combined claim inventory is missing its declared rows" }
            return emptyList()
        }
        require(inventory is JsonArray) { "Combined claim inventory must be an array" }
        if (declared == null) {
            return inventory.map { row ->
                require(row is JsonObject) { "Combined claim rows require a supported schema" }
                row
            }
        }
        require(declared == schema) { "Unsupported Combined claim inventory schema" }
        val decoded = inventory.map { decodeRow(it) }
        val seenAliases = mutableSetOf<String>()
        decoded.forEach { claim ->
            (claim.getValue("aliases") as JsonArray).forEach { alias ->
                require(seenAliases.add(string(alias, "origin alias"))) { "Combined claim alias belongs to multiple rows" }
            }
        }
        return decoded
    }

    private fun decodeRow(element: JsonElement): JsonObject {
        val row = element as? JsonArray ?: throw IllegalArgumentException("Combined claim row must be an array")
        require(row.size in 3..6) { "Invalid Combined claim column count" }
        val id = string(row[0], "identity")
        require(aliasPattern.matches(id)) { "Invalid Combined claim identity" }
        val text = string(row[1], "text")
        val origins = row[2] as? JsonArray ?: throw IllegalArgumentException("Combined claim origins must be an array")
        require(origins.isNotEmpty()) { "Combined claim has no origins" }
        val decodedOrigins = origins.map { element ->
            val origin = element as? JsonArray ?: throw IllegalArgumentException("Combined claim origin must be an array")
            require(origin.size in 0..3) { "Invalid Combined origin column count" }
            val explicitAlias = origin.size == 1 || origin.size == 3
            val alias = if (explicitAlias) string(origin[0], "origin alias") else id
            require(aliasPattern.matches(alias)) { "Invalid Combined origin alias" }
            val offset = if (explicitAlias) 1 else 0
            val span = if (origin.size - offset == 2) {
                val start = integer(origin[offset])
                val end = integer(origin[offset + 1])
                require(start >= 0 && end > start) { "Invalid Combined origin range" }
                listOf(JsonPrimitive(start), JsonPrimitive(end))
            } else {
                emptyList()
            }
            JsonArray(listOf(JsonPrimitive(alias)) + span)
        }
        val aliases = decodedOrigins.map { string(it[0], "origin alias") }
        require(aliases.distinct().size == aliases.size) { "Combined claim repeats an origin alias" }
        require(id in aliases) { "Combined claim has no canonical origin" }
        val qualifiers = row.getOrNull(3) ?: defaults[0]
        require(qualifiers is JsonObject) { "Combined claim qualifiers must be an object" }
        // Validate all qualification fields, including nondefault values, before downstream use.
        try {
            Json.decodeFromJsonElement(CombinedClaimQualifiers.serializer(), qualifiers)
        } catch (_: SerializationException) {
            // Serialization errors may quote private qualifier values or the whole JSON input.
            throw IllegalArgumentException("Invalid Combined claim qualifiers")
        }
        val evidenceStatus = string(row.getOrNull(4) ?: defaults[1], "evidence status")
        val supportingIds = row.getOrNull(5) ?: defaults[2]
        require(supportingIds is JsonArray) { "Combined evidence IDs must be an array" }
        supportingIds.forEach { string(it, "evidence ID") }
        return buildJsonObject {
            put("id", id)
            put("text", text)
            put("aliases", JsonArray(aliases.map(::JsonPrimitive)))
            put("qualifiers", qualifiers)
            put("contributors", JsonArray(aliases.map { it.substringBefore('.') }.distinct().map(::JsonPrimitive)))
            put("source_origins_utf16", JsonArray(decodedOrigins))
            if (decodedOrigins.all { it.size == 3 }) {
                put("source_ranges_utf16", JsonArray(decodedOrigins.map { JsonArray(listOf(JsonPrimitive(string(it[0], "origin alias").substringBefore('.')), it[1], it[2])) }))
            }
            put("evidence_status", evidenceStatus)
            put("supporting_evidence_ids", supportingIds)
        }
    }

    private fun string(element: JsonElement, field: String): String {
        val value = element as? JsonPrimitive
        require(value?.isString == true && value.content.isNotBlank()) { "Invalid Combined claim $field" }
        return value.content
    }

    private fun integer(element: JsonElement): Int {
        val value = element as? JsonPrimitive
        require(value != null && !value.isString) { "Invalid Combined origin offset" }
        return requireNotNull(value.intOrNull) { "Invalid Combined origin offset" }
    }
}
