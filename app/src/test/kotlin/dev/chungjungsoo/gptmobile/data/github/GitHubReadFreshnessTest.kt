package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadFreshnessTest {
    @Test fun pinnedSourceAndMutableStateUseDistinctFreshnessDecisions() {
        val immutable = GitHubResponseCache.Entry("etag", JsonObject(emptyMap()), 1000, immutable = true)
        val mutable = immutable.copy(immutable = false)
        assertEquals(GitHubReadFreshness.IMMUTABLE_SHA_REUSE, GitHubReadFreshness.decision(false, immutable, false, false, false))
        assertEquals(GitHubReadFreshness.EXPLICIT_LIVE_READ, GitHubReadFreshness.decision(true, immutable, false, true, false))
        assertEquals(GitHubReadFreshness.FRESH_INVENTORY_REUSE, GitHubReadFreshness.decision(false, mutable, true, true, false))
        assertEquals(GitHubReadFreshness.INVENTORY_TTL_EXPIRED, GitHubReadFreshness.decision(false, mutable, true, false, false))
        assertEquals(GitHubReadFreshness.MUTABLE_STATE_REVALIDATION, GitHubReadFreshness.decision(false, mutable, false, true, false))
        assertEquals(GitHubReadFreshness.CACHE_MISS, GitHubReadFreshness.decision(false, null, false, false, false))
    }

    @Test fun mutationInvalidationPreservesPinnedSourceAndClearsWhenRefreshed() {
        val cache = GitHubResponseCache(maxEntries = 2)
        cache.put("repo:pr", "old", JsonPrimitive("old"))
        cache.put("repo:sha", "fixed", JsonPrimitive("immutable"), immutable = true)
        cache.invalidateMutable("repo:")
        assertNull(cache.get("repo:pr"))
        assertTrue(cache.invalidatedByMutation("repo:pr"))
        assertFalse(cache.invalidatedByMutation("repo:sha"))
        assertEquals(JsonPrimitive("immutable"), cache.get("repo:sha")?.value)
        assertEquals(GitHubReadFreshness.MUTATION_INVALIDATED, GitHubReadFreshness.decision(false, null, false, false, cache.invalidatedByMutation("repo:pr")))
        cache.put("repo:pr", "new", JsonPrimitive("new"))
        assertFalse(cache.invalidatedByMutation("repo:pr"))
        cache.invalidateMutable()
        cache.clear()
        assertFalse(cache.invalidatedByMutation("repo:pr"))
    }

    @Test fun freshnessReceiptsAreBoundedAndCannotClaimLiveReadsOrExposePaths() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        withContext(accounting) {
            repeat(40) { GitHubDispatchAccounting.freshness("/repos/private/name/issues/$it?secret=value", GitHubReadFreshness.MUTATION_INVALIDATED, 1000) }
        }
        val receipt = requireNotNull(accounting.finish(AgentToolResult("read", ToolResultContent.Text("retained"), false), false).executionReceipt)
        assertEquals(32, (receipt["read_freshness"] as JsonArray).size)
        assertEquals(JsonPrimitive(8), receipt["omitted_read_freshness"])
        assertEquals(JsonPrimitive(false), receipt["live_read_performed"])
        assertEquals(JsonPrimitive(0), receipt["read_requests"])
        assertFalse(receipt.toString().contains("private/name"))
        assertFalse(receipt.toString().contains("secret"))
    }
}
