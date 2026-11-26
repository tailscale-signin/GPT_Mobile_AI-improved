package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubCiTaskScopeTest {
    private val head = "a".repeat(40)
    private fun call(action: String, arguments: JsonObject = JsonObject(emptyMap())) = ProviderEvent.ToolCall("request", "github_ci__selected", JsonObject(arguments + ("action" to JsonPrimitive(action))))
    private fun scope(call: ProviderEvent.ToolCall, receipts: Map<String, JsonObject> = emptyMap()) = GitHubNextActionPolicy.scopedCiRead(call, head, "owner/repo", "main", receipts)
    private fun observed(sha: String = head, attempt: Int = 2) = mapOf(
        "runs" to buildJsonObject {
            put("tool", "github_ci__selected")
            put("repository", "owner/repo")
            put("trusted", true)
            put("success", true)
            put("verification_eligible", true)
            put(
                "result",
                buildJsonObject {
                    put(
                        "workflow_runs",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("id", 17)
                                    put("run_attempt", attempt)
                                    put("head_sha", sha)
                                }
                            )
                        )
                    )
                }
            )
        }
    )

    @Test fun anUnpinnedRunQueryGetsTheAcceptedHeadAndCannotBroadenToHistoricalFailures() {
        val args = requireNotNull(scope(call("runs", buildJsonObject { put("status", "failure") }))).arguments
        assertEquals(JsonPrimitive(head), args["head_sha"])
        assertEquals(JsonPrimitive("owner"), args["owner"])
        assertNull(scope(call("runs", buildJsonObject { put("head_sha", "b".repeat(40)) })))
        assertNull(scope(call("runs", buildJsonObject { put("ref", "another-branch") })))
        assertNull(
            scope(
                call(
                    "runs",
                    buildJsonObject {
                        put("owner", "another")
                        put("repo", "repo")
                    }
                )
            )
        )
    }

    @Test fun observedRunIdentityPinsTheCurrentAttemptAndRejectsKnownHistoricalOrOlderAttempts() {
        val requested = call("jobs", buildJsonObject { put("run_id", 17) })
        val args = requireNotNull(scope(requested, observed())).arguments
        assertEquals(JsonPrimitive(2), args["run_attempt"])
        assertEquals(JsonPrimitive(head), args["expected_head_sha"])
        assertNull(scope(requested, observed("b".repeat(40))))
        assertNull(scope(requested.copy(arguments = JsonObject(requested.arguments + ("run_attempt" to JsonPrimitive(1)))), observed()))
        assertNotNull(scope(requested.copy(arguments = JsonObject(requested.arguments + ("run_attempt" to JsonPrimitive(3)))), observed()))
    }
}
