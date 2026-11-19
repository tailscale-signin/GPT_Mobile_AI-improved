package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Each extraction is scoped to a small group of acceptance requirements. */
internal object GitHubClaimBatches {
    const val REQUIREMENTS_PER_BATCH = 4
    const val MAX_BATCHES = 8
    const val MAX_CLAIMS = 8

    data class Request(val payload: String, val requirementIds: Set<String>, val maxClaims: Int = MAX_CLAIMS)

    /** Encode arbitrary GitHub values as JSON text so strict providers can constrain every field. */
    fun responseSchemaJson(): String = wireSchema

    private val wireSchema: String by lazy {
        val string = schemaType("string")
        val nullableString = buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }
        val source = record("receipt_id" to string, "pointer" to string, "value_json" to string)
        val kinds = listOf("fact", "excerpt", "inferred", "calculation", "recommendation", "not_measured")
        val claim = record(
            "kind" to JsonObject(string + ("enum" to JsonArray(kinds.map(::JsonPrimitive)))),
            "receipt_id" to nullableString,
            "pointer" to nullableString,
            "value_json" to nullableString,
            "text" to nullableString,
            "sources" to arrayOfItems(source),
            "subject" to nullableString,
            "operation" to nullableString
        )
        val coverage = record("requirement_id" to string, "claim_indexes" to arrayOfItems(schemaType("integer")))
        val group = record("role" to string, "receipt_ids" to arrayOfItems(string))
        val acceptance = record("requirement_id" to string, "groups" to arrayOfItems(group))
        buildJsonObject {
            put("type", "json_schema")
            put(
                "json_schema",
                buildJsonObject {
                    put("name", "github_evidence_claims_v2")
                    put("strict", true)
                    put("schema", record("claims" to arrayOfItems(claim), "requirement_claims" to arrayOfItems(coverage), "acceptance" to arrayOfItems(acceptance), "questions" to arrayOfItems(string)))
                }
            )
        }.toString()
    }

    private fun schemaType(type: String) = buildJsonObject { put("type", type) }
    private fun arrayOfItems(item: JsonObject) = buildJsonObject {
        put("type", "array")
        put("items", item)
    }

    private fun record(vararg fields: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(fields.toMap()))
        put("required", JsonArray(fields.map { JsonPrimitive(it.first) }))
        put("additionalProperties", false)
    }

    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][+-]?[0-9]+)?")

    // The JSON tree reader can preserve bare, unquoted tokens. A source value must be valid JSON.
    private fun validLiterals(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.values.all(::validLiterals)
        is JsonArray -> value.all(::validLiterals)
        is JsonPrimitive -> value.isString || value == JsonNull || value.content in setOf("true", "false") || jsonNumber.matches(value.content)
    }

    private fun normalizeSource(source: JsonObject): JsonObject {
        if ("value_json" !in source) return source
        require("value" !in source)
        val encoded = source["value_json"]
        val rest = source - "value_json"
        if (encoded == JsonNull) return JsonObject(rest)
        val value = requireNotNull((encoded as? JsonPrimitive)?.takeIf(JsonPrimitive::isString))
        val decoded = kotlinx.serialization.json.Json.parseToJsonElement(value.content)
        require(validLiterals(decoded))
        return JsonObject(rest + ("value" to decoded))
    }

    private fun normalizeClaim(entry: JsonElement): JsonObject {
        val claim = normalizeSource(requireNotNull(entry as? JsonObject))
        val sources = claim["sources"] as? JsonArray ?: return claim
        return JsonObject(claim + ("sources" to JsonArray(sources.map { normalizeSource(requireNotNull(it as? JsonObject)) })))
    }

    private fun entryMap(rows: JsonArray, key: String, value: (JsonObject) -> JsonElement): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        for (element in rows) {
            val row = requireNotNull(element as? JsonObject)
            val id = row.string(key)
            require(id.isNotBlank() && id !in result)
            result[id] = value(row)
        }
        return JsonObject(result)
    }

    fun withClaimLimit(request: Request, limit: Int): Request {
        require(limit in 1 until request.maxClaims)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(request.payload) as JsonObject
        val reduced = JsonObject(parsed + ("claim_limit" to JsonPrimitive(limit)))
        return request.copy(payload = reduced.toString(), maxClaims = limit)
    }

    fun validate(answer: JsonObject, request: Request): JsonObject {
        val claims = JsonArray(requireNotNull(answer["claims"] as? JsonArray).map(::normalizeClaim))
        require(request.maxClaims in 1..MAX_CLAIMS && claims.size in 1..request.maxClaims)
        claims.forEach { entry ->
            val claim = requireNotNull(entry as? JsonObject)
            when (claim.string("kind")) {
                "fact", "excerpt" -> validateSource(claim)
                "inferred", "calculation", "recommendation" -> {
                    if (claim.string("kind") in setOf("inferred", "recommendation")) require(claim.string("text").length in 1..1200)
                    val sources = requireNotNull(claim["sources"] as? JsonArray)
                    require(sources.size in 1..16)
                    sources.forEach { validateSource(requireNotNull(it as? JsonObject)) }
                }
                "not_measured" -> require(claim.string("subject").length in 1..160)
                else -> error("Unsupported claim kind")
            }
        }
        val coverage = (answer["requirement_claims"] as? JsonArray)?.let { rows ->
            entryMap(rows, "requirement_id") { it.getValue("claim_indexes") }
        } ?: objectField(answer, "requirement_claims")
        val acceptance = (answer["acceptance"] as? JsonArray)?.let { rows ->
            entryMap(rows, "requirement_id") { entry ->
                entryMap(requireNotNull(entry["groups"] as? JsonArray), "role") { it.getValue("receipt_ids") }
            }
        } ?: objectField(answer, "acceptance")
        require(request.requirementIds.containsAll(coverage.keys) && request.requirementIds.containsAll(acceptance.keys))
        coverage.values.forEach { value ->
            val indexes = requireNotNull(value as? JsonArray)
            require(indexes.all { entry -> (entry as? JsonPrimitive)?.intOrNull?.let { it in claims.indices } == true })
        }
        acceptance.values.forEach { value ->
            val mapping = requireNotNull(value as? JsonObject)
            require(mapping.values.all { ids -> ids is JsonArray && ids.size <= 32 && ids.all { it is JsonPrimitive && it.isString } })
        }
        val questions = when (val value = answer["questions"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.map { requireNotNull((it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)).content.take(240) }.take(2)
            else -> error("Invalid extraction questions")
        }
        return buildJsonObject {
            put("claims", claims)
            put("requirement_claims", coverage)
            put("acceptance", acceptance)
            put("questions", JsonArray(questions.map(::JsonPrimitive)))
        }
    }

    /** Convert batch-local indexes to the final answer's deduplicated claim indexes. */
    fun combine(batches: List<JsonObject>): JsonObject {
        val claims = linkedMapOf<JsonElement, Int>()
        val coverage = linkedMapOf<String, JsonElement>()
        val acceptance = linkedMapOf<String, JsonElement>()
        val questions = linkedSetOf<JsonElement>()
        batches.forEach { batch ->
            val indexes = (batch.getValue("claims") as JsonArray).map { claim -> claims.getOrPut(claim) { claims.size } }
            (batch.getValue("requirement_claims") as JsonObject).forEach { (id, local) ->
                coverage[id] = JsonArray((local as JsonArray).map { JsonPrimitive(indexes[(it as JsonPrimitive).intOrNull!!]) }.distinct())
            }
            acceptance.putAll(batch.getValue("acceptance") as JsonObject)
            questions.addAll(batch.getValue("questions") as JsonArray)
        }
        return buildJsonObject {
            put("claims", JsonArray(claims.keys.toList()))
            put("requirement_claims", JsonObject(coverage))
            put("acceptance", JsonObject(acceptance))
            put("questions", JsonArray(questions.take(2)))
        }
    }

    private fun validateSource(source: JsonObject) {
        require(source.string("receipt_id").isNotBlank() && source.string("pointer").startsWith('/') && "value" in source)
    }

    private fun objectField(answer: JsonObject, name: String): JsonObject = when (val value = answer[name]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> error("Invalid extraction mapping")
    }

    private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content.orEmpty()
}
