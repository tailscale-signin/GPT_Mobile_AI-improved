package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubObservationFreshnessTest {
    @Test fun derivedClaimsRefreshOnlyTheirExpiredOperands() {
        val report = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"unresolved_claims":[{"status":"NOT_MEASURED","sources":[{"receipt_id":"old","status":"STALE"},{"receipt_id":"current","status":"VERIFIED"}]},{"receipt_id":"old","status":"STALE"}]}"""
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(listOf("old"), GitHubExecutionContinuation.staleReceiptIds(report))
    }

    @Test fun staleClaimSelectsOnlyItsExactMutableReadAndDoesNotRepeatAfterRestart() {
        fun json(raw: String) = kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val schema = json("""{"type":"object","properties":{"action":{"type":"string"},"issue_number":{"type":"integer"},"verify_live":{"type":"boolean"}},"required":["action","issue_number"]}""")
        val tool = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("github", "read", schema)
        val original = json("""{"trusted":true,"success":true,"tool":"github","arguments":{"action":"get_issue","issue_number":42}}""")
        val observations = mapOf("old" to original)
        val call = requireNotNull(GitHubExecutionContinuation.staleRead(listOf("old"), listOf(tool), observations, "request"))
        assertEquals(json("""{"action":"get_issue","issue_number":42,"verify_live":true}"""), call.arguments)
        val after = observations + ("fresh" to json("""{"call_id":"${call.callId}"}"""))
        assertNull(GitHubExecutionContinuation.staleRead(listOf("old"), listOf(tool), after, "request"))
        assertNull(GitHubExecutionContinuation.staleRead(listOf("unrelated"), listOf(tool), observations, "request"))
        for (change in listOf("""{"immutable":true}""", """{"trusted":false}""", """{"verification_eligible":false}""", """{"arguments":{"action":"close_issue","issue_number":42}}""")) {
            val excluded = kotlinx.serialization.json.JsonObject(original + json(change))
            assertNull(GitHubExecutionContinuation.staleRead(listOf("old"), listOf(tool), mapOf("old" to excluded), "request"))
        }
    }

    @Test fun immutableLookupCannotAgeRevalidatedMutableObservations() = runTest {
        for (status in listOf(200, 304)) {
            val accounting = GitHubDispatchAccounting()
            withContext(accounting) {
                GitHubDispatchAccounting.cached(1000, immutable = true)
                GitHubDispatchAccounting.started(false)
                GitHubDispatchAccounting.responded(false, status)
            }
            val receipt = accounting.finish(AgentToolResult("checks", ToolResultContent.Text("ok"), false), false).executionReceipt!!
            assertEquals(JsonPrimitive(1), receipt["cached_read_observations"])
            assertEquals(JsonPrimitive(status), receipt["http_status"])
            assertNull(receipt["source_observed_at_ms"])
        }
    }

    @Test fun mutableCachedDependenciesAndPureImmutableReplaysKeepTheirOriginalTime() = runTest {
        val mutable = GitHubDispatchAccounting()
        withContext(mutable) {
            GitHubDispatchAccounting.cached(1000, immutable = true)
            GitHubDispatchAccounting.cached(5000)
            GitHubDispatchAccounting.started(false)
            GitHubDispatchAccounting.responded(false, 200)
        }
        val result = AgentToolResult("cached", ToolResultContent.Text("ok"), false)
        assertEquals(JsonPrimitive(5000), mutable.finish(result, false).executionReceipt!!["source_observed_at_ms"])
        val immutable = GitHubDispatchAccounting()
        withContext(immutable) { GitHubDispatchAccounting.cached(1000, immutable = true) }
        assertEquals(JsonPrimitive(1000), immutable.finish(result, false).executionReceipt!!["source_observed_at_ms"])
    }
}
