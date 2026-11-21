package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubExecutionContinuationFindingTest {
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
