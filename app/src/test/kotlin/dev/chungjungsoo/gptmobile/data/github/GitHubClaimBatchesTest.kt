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
    @Test fun missingOptionalPointerIsOmittedOnlyWithCompleteExplicitCoverage() {
        val task = "Report the observed count"
        val requirement = GitHubRequestCompletion.requirements(task).single()
        val key = GitHubRequestCompletion.id(requirement)
        fun report(indexes: String): kotlinx.serialization.json.JsonObject {
            val answer = Json.parseToJsonElement("""{"claims":[{"kind":"fact","receipt_id":"r","pointer":"/count","value":43},{"kind":"fact","receipt_id":"r","pointer":"/absent","value":5}],"requirement_claims":{"$key":$indexes}}""").jsonObject
            val receipt = Json.parseToJsonElement("""{"trusted":true,"success":true,"observed_at_ms":1000,"epoch":1,"result":{"count":43}}""").jsonObject
            return GitHubEvidencePolicy.render(answer, mapOf("r" to receipt), 1000, 1, task).second
        }
        val optional = report("[0]")
        assertEquals(JsonPrimitive(1), optional["omitted_optional_claim_count"])
        assertEquals(1, (optional.getValue("claims") as JsonArray).size)
        val required = report("[0,1]")
        assertEquals(2, (required.getValue("claims") as JsonArray).size)
        assertEquals(JsonPrimitive(false), required["completion_verified"])
    }

    @Test fun oneMalformedClaimCannotEraseItsNeighboursOrRetargetTheirIndexes() {
        val answer = Json.parseToJsonElement("""{"claims":[{"kind":"fact","pointer":"wrong","receipt_id":"r","value":1},{"kind":"fact","pointer":"/valid","receipt_id":"r","value":2}],"requirement_claims":{"a":[0],"b":[1]}}""").jsonObject
        val batch = GitHubClaimBatches.validate(answer, GitHubClaimBatches.Request("", setOf("a", "b")))
        assertEquals(1, (batch.getValue("claims") as JsonArray).size)
        assertEquals(Json.parseToJsonElement("""{"a":[],"b":[0]}"""), batch["requirement_claims"])
    }

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
