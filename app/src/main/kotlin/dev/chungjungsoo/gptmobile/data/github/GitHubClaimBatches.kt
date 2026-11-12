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

    /** OpenAI-compatible JSON Schema response format; other providers keep the same local validator. */
    fun responseSchemaJson(): String = """{"type":"json_schema","json_schema":{"name":"github_evidence_claims","strict":true,"schema":{"type":"object","properties":{"claims":{"type":"array","items":{"type":"object","properties":{"kind":{"type":"string","enum":["fact","excerpt","inferred","calculation","recommendation","not_measured"]},"receipt_id":{"type":["string","null"]},"pointer":{"type":["string","null"]},"value":{},"text":{"type":["string","null"]},"sources":{"type":["array","null"],"items":{"type":"object","properties":{"receipt_id":{"type":"string"},"pointer":{"type":"string"},"value":{}},"required":["receipt_id","pointer","value"],"additionalProperties":false}},"subject":{"type":["string","null"]},"operation":{"type":["string","null"]}},"required":["kind","receipt_id","pointer","value","text","sources","subject","operation"],"additionalProperties":false}},"requirement_claims":{"type":"object","additionalProperties":{"type":"array","items":{"type":"integer"}}},"acceptance":{"type":"object","additionalProperties":{"type":"object","additionalProperties":{"type":"array","items":{"type":"string"}}}},"questions":{"type":"array","items":{"type":"string"}}},"required":["claims","requirement_claims","acceptance","questions"],"additionalProperties":false}}}"""

    fun withClaimLimit(request: Request, limit: Int): Request {
        require(limit in 1 until request.maxClaims)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(request.payload) as JsonObject
        val reduced = JsonObject(parsed + ("claim_limit" to JsonPrimitive(limit)))
        return request.copy(payload = reduced.toString(), maxClaims = limit)
    }

    fun validate(answer: JsonObject, request: Request): JsonObject {
        val claims = requireNotNull(answer["claims"] as? JsonArray)
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
        val coverage = objectField(answer, "requirement_claims")
        val acceptance = objectField(answer, "acceptance")
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
