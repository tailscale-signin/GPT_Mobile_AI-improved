package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerificationSemanticsTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun eighteenSupportedFactsAndAnExplicitCoverageGapRemainAPartialIntegration() {
        val receipts = listOf("status", "branch", "resolve").mapIndexed { index, action ->
            val id = "observation-$index"
            id to buildJsonObject {
                put("receipt_id", id)
                put("trusted", true)
                put("success", true)
                put("observed_at_ms", 1000)
                put("epoch", 0)
                put("tool", "github_context")
                put("arguments", buildJsonObject { put("action", action) })
                put(
                    "result",
                    buildJsonObject {
                        put("status", "completed")
                        put("state", "open")
                        put("checks", "pending")
                        put("conclusion", "success")
                        put("head_sha", "a".repeat(40))
                        put("commit_sha", "a".repeat(40))
                    }
                )
            }
        }.toMap()
        val (_, report) = GitHubEvidencePolicy.testing(receipts, 1000, 0, "Test all authorized GitHub integration tools and verify cleanup.")
        assertEquals(JsonPrimitive(18), report["verified_claim_count"])
        assertEquals(JsonPrimitive(true), report["claims_verified"])
        assertEquals(JsonPrimitive(true), report["answer_verified"])
        assertTrue(report.getValue("unresolved_claims").jsonArray.isEmpty())
        val gap = report.getValue("unmeasured_scope").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("NOT_MEASURED"), gap["status"])
        assertEquals(JsonPrimitive("Full requested test coverage requires execution evidence"), gap["subject"])
        assertEquals(19, report.getValue("claims").jsonArray.size)
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertEquals(JsonPrimitive(true), report["partial"])
        assertEquals(JsonPrimitive("PARTIAL"), report["task_outcome"])
        assertFalse(report.getValue("remaining_requirements").jsonArray.isEmpty())
    }

    @Test fun anUnsupportedFactCannotMasqueradeAsAnExplicitUnmeasuredScope() {
        val answer = buildJsonObject {
            put(
                "claims",
                JsonArray(
                    listOf(
                        json("""{"kind":"fact","receipt_id":"missing","pointer":"/status","value":"passed"}"""),
                        json("""{"kind":"not_measured","subject":"Current-device performance"}""")
                    )
                )
            )
        }
        val (_, report) = GitHubEvidencePolicy.render(answer, emptyMap(), 1000, 0, "Explain the observed test result")
        assertEquals(JsonPrimitive(false), report["claims_verified"])
        assertEquals(JsonPrimitive(false), report["answer_verified"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertEquals(1, report.getValue("unresolved_claims").jsonArray.size)
        assertEquals(JsonPrimitive("fact"), report.getValue("unresolved_claims").jsonArray.single().jsonObject["kind"])
        assertEquals(1, report.getValue("unmeasured_scope").jsonArray.size)
    }
}
