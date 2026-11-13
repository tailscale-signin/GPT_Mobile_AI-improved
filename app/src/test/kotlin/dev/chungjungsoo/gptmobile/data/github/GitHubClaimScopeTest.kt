package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClaimScopeTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val receipt = json("""{"trusted":true,"success":true,"immutable":true,"epoch":0,"observed_at_ms":1000,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"${"a".repeat(40)}"}}""")
    private val fact = json("""{"kind":"fact","receipt_id":"source","pointer":"/commit_sha","value":"${"a".repeat(40)}"}""")
    private val limitation = json("""{"kind":"not_measured","subject":"Device test execution"}""")

    @Test fun explicitUnmeasuredScopeDoesNotInvalidateVerifiedFactsOrCompleteMissingWork() {
        val answer = JsonObject(mapOf("claims" to JsonArray(listOf(fact, limitation))))
        val (text, report) = GitHubEvidencePolicy.render(answer, mapOf("source" to receipt), 1100, 0, "Implement the requested feature and run device tests")
        assertEquals(JsonPrimitive(true), report["claims_verified"])
        assertEquals(JsonPrimitive(true), report["answer_verified"])
        assertTrue((report["unresolved_claims"] as JsonArray).isEmpty())
        assertEquals(JsonPrimitive("Device test execution"), ((report["unmeasured_scope"] as JsonArray).single() as JsonObject)["subject"])
        assertTrue(text.contains("Device test execution"))
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertEquals(JsonPrimitive("PARTIAL"), report["task_outcome"])
    }

    @Test fun missingEvidenceForAnAffirmativeFactIsStillUnresolved() {
        val unsupported = JsonObject(fact + ("receipt_id" to JsonPrimitive("missing")))
        val answer = JsonObject(mapOf("claims" to JsonArray(listOf(unsupported, limitation))))
        val (_, report) = GitHubEvidencePolicy.render(answer, mapOf("source" to receipt), 1100, 0, "Report the current commit")
        assertEquals(JsonPrimitive(false), report["claims_verified"])
        assertEquals(JsonPrimitive(false), report["answer_verified"])
        val unresolved = (report["unresolved_claims"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("fact"), unresolved["kind"])
        assertEquals(JsonPrimitive("RECEIPT_NOT_FOUND"), unresolved["reason"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
    }

    @Test fun limitationsAloneNeverProduceVerifiedClaimsOrACompletedTask() {
        val answer = JsonObject(mapOf("claims" to JsonArray(listOf(limitation))))
        val (_, report) = GitHubEvidencePolicy.render(answer, emptyMap(), 1100, 0, "Report device tests")
        assertEquals(JsonPrimitive(false), report["claims_verified"])
        assertEquals(JsonPrimitive(false), report["answer_verified"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertFalse(GitHubEvidencePolicy.canReuseExtractionBatch(answer, emptyMap(), 1100, 0, "Report device tests"))
    }

    @Test fun savedBatchWithVerifiedFactsAndAnExplicitLimitationCanBeReused() {
        val answer = JsonObject(mapOf("claims" to JsonArray(listOf(fact, limitation))))
        assertTrue(GitHubEvidencePolicy.canReuseExtractionBatch(answer, mapOf("source" to receipt), 1100, 0, "Report the current commit"))
        assertFalse(GitHubEvidencePolicy.canReuseExtractionBatch(answer, emptyMap(), 1100, 0, "Report the current commit"))
    }
}
