package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceRoutePolicy.Route
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEvidenceRoutePolicyTest {
    private val all = Route.entries.toSet()

    @Test fun verifiedYieldAndElapsedTimeChangeOnlyAdmittedRoutesForTheSameProofType() = runBlocking {
        var time = 0L
        val policy = GitHubEvidenceRoutePolicy { time }
        val kind = "DEFECT_ANALYSIS"
        suspend fun observe(route: Route, millis: Long, state: String) {
            policy.started(kind, route, "one")
            time += millis
            policy.observed(Json.parseToJsonElement("""{"acceptance_ledger":{"requirements":[{"id":"one","required":true,"state":"$state"}]}}""") as JsonObject)
        }
        assertEquals(Route.CACHED_RANGE, policy.choose(kind, all).first())
        observe(Route.CACHED_RANGE, 100, "BLOCKED")
        assertEquals(Route.LIVE_LOOKUP, policy.choose(kind, all).first())
        observe(Route.LIVE_LOOKUP, 25_000, "PASS")
        assertEquals(Route.REVIEWER, policy.choose(kind, all).first())
        observe(Route.REVIEWER, 100, "PASS")
        assertEquals(Route.REVIEWER, policy.choose(kind, all).first())
        assertEquals(listOf(Route.LIVE_LOOKUP), policy.choose(kind, setOf(Route.LIVE_LOOKUP)))
        assertEquals(listOf(Route.CACHED_RANGE, Route.LIVE_LOOKUP), policy.choose("SOURCE_OBSERVATION", all))
        assertEquals(listOf(Route.LIVE_LOOKUP), policy.choose("VALIDATION_JOBS", all))
    }

    @Test fun measuredTokensDistinguishFreeUnknownAndExpensiveRoutesWithIdenticalProofAndTime() = runBlocking {
        var tokens: Int? = 10
        val policy = GitHubEvidenceRoutePolicy(measuredRunTokens = { tokens }, monotonicMillis = { 100L })
        val kind = "DEFECT_ANALYSIS"
        val passed = Json.parseToJsonElement("""{"acceptance_ledger":{"requirements":[{"id":"gap","required":true,"state":"PASS"}]}}""") as JsonObject
        policy.review(kind, "gap") {
            tokens = 9010
            true
        }
        policy.observed(passed)
        tokens = null
        policy.started(kind, Route.CACHED_RANGE, "gap")
        policy.observed(passed)
        tokens = 9010
        policy.started(kind, Route.LIVE_LOOKUP, "gap")
        policy.observed(passed)
        assertEquals(listOf(Route.LIVE_LOOKUP, Route.CACHED_RANGE, Route.REVIEWER), policy.choose(kind, all))
    }

    @Test fun cachedRangeNeedsTheAuthorizedReaderAnImmutableRevisionAndCurrentEpoch() {
        val head = "a".repeat(40)
        val tool = "github_read__github_api"
        val row = Json.parseToJsonElement("""{"tool":"$tool","trusted":true,"verification_eligible":true,"success":true,"immutable":true,"repository_role":"WRITE_DESTINATION","epoch":4,"arguments":{"commit_sha":"$head","ranges":[{"path":"a.kt"}]}}""") as JsonObject
        fun ok(receipt: JsonObject = row, epoch: Long = 4, sha: String = head, allowed: Set<String> = setOf(tool)) =
            GitHubEvidenceRoutePolicy.cachedRange(receipt, epoch, sha, allowed)
        assertTrue(ok())
        assertFalse(ok(allowed = emptySet()))
        assertFalse(ok(epoch = 5))
        assertFalse(ok(sha = "b".repeat(40)))
        assertFalse(ok(sha = "main"))
        listOf("verification_eligible", "trusted", "success", "immutable").forEach { assertFalse(ok(JsonObject(row + (it to JsonPrimitive(false))))) }
    }
}
