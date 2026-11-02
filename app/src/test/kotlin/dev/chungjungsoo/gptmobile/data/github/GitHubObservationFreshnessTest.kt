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
