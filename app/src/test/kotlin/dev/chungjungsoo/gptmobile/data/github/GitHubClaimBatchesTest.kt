package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClaimBatchesTest {
    @Test fun strictWireSchemaClosesEveryObjectAndTypesEveryNode() {
        val root = Json.parseToJsonElement(GitHubClaimBatches.responseSchemaJson()).jsonObject
        fun verify(node: kotlinx.serialization.json.JsonObject) {
            assertTrue("type" in node)
            if (node["type"] == JsonPrimitive("object")) {
                val properties = node.getValue("properties").jsonObject
                assertEquals(JsonPrimitive(false), node["additionalProperties"])
                assertEquals(properties.keys, (node.getValue("required") as JsonArray).map { (it as JsonPrimitive).content }.toSet())
                properties.values.forEach { verify(it.jsonObject) }
            }
            node["items"]?.let { verify(it.jsonObject) }
        }
        verify(root.getValue("json_schema").jsonObject.getValue("schema").jsonObject)
    }

    @Test fun wireClaimValuesAndReceiptMappingsRetainTheirOriginalTypes() {
        val values = Json.parseToJsonElement("""[null,"42",42,true,[1],{"field":"x"}]""") as JsonArray
        val entries = values.map { value ->
            buildJsonObject {
                put("kind", "fact")
                put("receipt_id", "exact")
                put("pointer", "/field")
                put("value_json", value.toString())
            }
        }
        val mapping = Json.parseToJsonElement("""[{"requirement_id":"r","claim_indexes":[0]}]""")
        val input = buildJsonObject {
            put("claims", JsonArray(entries))
            put("requirement_claims", mapping)
            put("acceptance", Json.parseToJsonElement("""[{"requirement_id":"r","groups":[{"role":"test_receipts","receipt_ids":["exact"]}]}]"""))
        }
        val request = GitHubClaimBatches.Request("{}", setOf("r"))
        val answer = GitHubClaimBatches.validate(input, request)
        assertEquals(values, JsonArray((answer.getValue("claims") as JsonArray).map { it.jsonObject.getValue("value") }))
        assertEquals(Json.parseToJsonElement("""{"r":{"test_receipts":["exact"]}}"""), answer["acceptance"])
        assertTrue(runCatching { GitHubClaimBatches.validate(input, request.copy(requirementIds = emptySet())) }.isFailure)
        val duplicate = kotlinx.serialization.json.JsonObject(input + ("requirement_claims" to JsonArray((mapping as JsonArray) + mapping)))
        assertTrue(runCatching { GitHubClaimBatches.validate(duplicate, request) }.isFailure)
    }

    @Test fun jsonTextInsideAClaimMustContainValidLiterals() {
        val request = GitHubClaimBatches.Request("{}", emptySet())
        for (encoded in listOf("NaN", "[bare]", "01")) {
            val fact = buildJsonObject {
                put("kind", "fact")
                put("pointer", "/field")
                put("receipt_id", "read")
                put("value_json", encoded)
            }
            assertTrue(runCatching { GitHubClaimBatches.validate(buildJsonObject { put("claims", JsonArray(listOf(fact))) }, request) }.isFailure)
        }
    }

    @Test fun truncatedBatchCanBeRetriedAtSmallerStrictlyValidatedLimit() {
        val original = GitHubClaimBatches.Request(
            """{"claim_limit":8,"requirements":[]}""",
            setOf("requirement")
        )
        val reduced = GitHubClaimBatches.withClaimLimit(original, 4)
        assertEquals(4, reduced.maxClaims)
        assertTrue("format_correction" !in Json.parseToJsonElement(reduced.payload).jsonObject)
        val corrected = GitHubClaimBatches.withClaimLimit(reduced, 1, invalidResponse = true)
        val retry = Json.parseToJsonElement(corrected.payload).jsonObject
        assertEquals(JsonPrimitive(1), retry["claim_limit"])
        assertTrue(retry.getValue("format_correction").toString().contains("1 to 1 claims"))
        assertEquals(original.requirementIds, corrected.requirementIds)
        val firstRepair = GitHubClaimBatches.withClaimLimit(original, 4, invalidResponse = true)
        val afterOutputLimit = GitHubClaimBatches.withClaimLimit(firstRepair, 2)
        assertTrue("format_correction" !in Json.parseToJsonElement(afterOutputLimit.payload).jsonObject)
        assertEquals(JsonPrimitive(4), Json.parseToJsonElement(reduced.payload).jsonObject["claim_limit"])
        val invalid = buildJsonObject {
            put(
                "claims",
                JsonArray(
                    (1..5).map {
                        buildJsonObject {
                            put("kind", "not_measured")
                            put("subject", "measurement $it")
                        }
                    }
                )
            )
            put("requirement_claims", buildJsonObject { })
            put("acceptance", buildJsonObject { })
            put("questions", JsonArray(emptyList()))
        }
        assertTrue(runCatching { GitHubClaimBatches.validate(invalid, reduced) }.isFailure)
    }

    @Test fun responseSchemaIsStrictAndAllowsSeparateRecommendationClaims() {
        val schema = Json.parseToJsonElement(GitHubClaimBatches.responseSchemaJson()).jsonObject
        val wrapped = schema.getValue("json_schema").jsonObject
        assertEquals(JsonPrimitive(true), wrapped["strict"])
        assertTrue(wrapped.getValue("schema").toString().contains("recommendation"))
    }
}
