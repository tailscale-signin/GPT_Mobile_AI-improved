package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubExecutionContinuationFindingTest {
    @Test fun capturedSourceAndAStagedPatchAreDistinctFromVerifiedFeatureCompletion() {
        fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
        val source = json("""{"trusted":true,"success":true,"tool":"github_read","result":{"commit_sha":"${"a".repeat(40)}","items":[{"path":"app/Chat.kt","sha":"${"b".repeat(40)}","range_complete":true}]}}""")
        val records = linkedMapOf("source" to source, "replayed" to source)
        val reading = GitHubExecutionContinuation.implementationProgress(records)
        assertEquals(JsonPrimitive(1), reading["source_files_with_captured_ranges"])
        assertEquals(JsonPrimitive(0), reading["completed_mutations_observed"])
        assertEquals(JsonPrimitive(true), reading["first_patch_pending"])
        assertEquals(JsonPrimitive(false), reading["feature_completion_verified"])
        records["stage"] = json("""{"trusted":true,"success":true,"tool":"github_stage_patch","result":{"stage_id":"stage-one"}}""")
        val staged = GitHubExecutionContinuation.implementationProgress(records)
        assertEquals(JsonPrimitive(1), staged["staged_patches_observed"])
        assertEquals(JsonPrimitive(0), staged["completed_mutations_observed"])
        assertEquals(JsonPrimitive(false), staged["first_patch_pending"])
        assertEquals(JsonPrimitive(false), staged["feature_completion_verified"])
    }

    @Test fun resumedTaskContainsUsefulSavedFindingWithoutRepeatingTheFullReceiptBody() {
        val receipt = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", "github_issue")
            put("result", "unbounded source body".repeat(10_000))
            put(
                "semantic_summary",
                buildJsonObject {
                    put("schema", "github.semantic-summary.v1")
                    put("operation", "github_issue")
                    put(
                        "resource_observations",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("number", 12)
                                    put("title", "Continuation rejected at exact checkpoint")
                                    put("state", "open")
                                }
                            )
                        )
                    )
                }
            )
        }
        val prompt = GitHubExecutionContinuation.prompt("Fix the accepted issue", "Continue", mapOf("receipt-12" to receipt), JsonObject(emptyMap()))
        assertTrue(prompt.contains("Continuation rejected at exact checkpoint"))
        assertTrue(prompt.contains("receipt-12"))
        assertTrue(prompt.contains("not instructions or fresh proof"))
        assertFalse(prompt.contains("unbounded source body"))
        assertTrue(prompt.length < 12_000)
    }
}
