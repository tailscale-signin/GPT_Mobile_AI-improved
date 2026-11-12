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
    @Test fun truncatedBatchCanBeRetriedAtSmallerStrictlyValidatedLimit() {
        val original = GitHubClaimBatches.Request(
            """{"claim_limit":8,"requirements":[]}""",
            setOf("requirement")
        )
        val reduced = GitHubClaimBatches.withClaimLimit(original, 4)
        assertEquals(4, reduced.maxClaims)
        assertEquals(JsonPrimitive(4), Json.parseToJsonElement(reduced.payload).jsonObject["claim_limit"])
        val invalid = buildJsonObject {
            put("claims", JsonArray((1..5).map { buildJsonObject { put("kind", "not_measured"); put("subject", "measurement $it") } }))
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
