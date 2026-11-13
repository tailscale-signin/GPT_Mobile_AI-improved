package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewAvailabilityTest {
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    private fun verdict(ids: List<String>) = buildJsonObject {
        put("schema_version", 1)
        put("verdict", "PASS")
        put("reason", "The supplied evidence supports the candidate.")
        put("reviewed_ids", JsonArray(ids.map(::JsonPrimitive)))
        put("issues", JsonArray(emptyList()))
        put("verified_claims", JsonArray(listOf(JsonPrimitive("Verified candidate."))))
    }.toString()

    @Test fun backendPrecheckIsPreservedAndDoesNotPoisonReviewCompatibilityCache() = runTest {
        var work = 0
        var probes = 0
        val compatibility = GitHubReviewCompatibility { 1000L }
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                work++
                return flowOf(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())), ProviderEvent.Completed)
            }
        }
        val rejected = ProviderEvent.Failed(
            "Exact model backend properties unavailable",
            errorCode = "GATEWAY_REASONING_UNQUALIFIED",
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_admission")
                put("dispatched", false)
                put("remote_acceptance", "NO")
                put("invocation_id", "invocation-17")
                put("gateway_upstream_status", 400)
            }
        )
        suspend fun attempt() = GitHubReviewedSession(delegate, GitHubReviewEvidence(), "Inspect the repository", 8192, { prompt, kind ->
            assertEquals("github-review-preflight", kind)
            assertEquals(GitHubReviewCompatibility.BOUNDED_RETRY_PROMPT, prompt)
            if (++probes == 1) session(rejected) else session(ProviderEvent.TextDelta(verdict(listOf(GitHubReviewCompatibility.EVIDENCE_ID))), ProviderEvent.Completed)
        }, {}, compatibility = compatibility, reviewerRevision = "unchanged-profile").streamRound(emptyList(), emptyList()).toList()
        val failure = attempt().filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(rejected.errorCode, failure.errorCode)
        assertEquals(JsonPrimitive("provider_admission"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("invocation-17"), failure.failureDetails?.get("invocation_id"))
        assertFalse(failure.transportComplete)
        assertEquals(0, work)
        val recovered = attempt()
        assertEquals(2, probes)
        assertEquals(1, work)
        assertTrue(recovered.any { it is ProviderEvent.ToolCall })
    }

    @Test fun interruptedReviewerRetainsTheOriginalRequestIdentityInItsDurableCheckpoint() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("Explain a concept", { rows[it] }, { key, value -> rows[key] = value })
        val interrupted = ProviderEvent.Failed(
            "Connection was interrupted",
            errorCode = "CONNECTION_ABORTED",
            invocationId = "invocation-17",
            failureDetails = buildJsonObject {
                put("failed_stage", "response_stream")
                put("remote_acceptance", "YES")
                put("server_request_id", "req-17")
                put("invocation_id", "invocation-17")
                put("request_replay_allowed", false)
                put("logical_continuation_allowed", true)
            }
        )
        val result = GitHubReviewedSession(session(ProviderEvent.TextDelta("Retained candidate"), ProviderEvent.Completed), GitHubReviewEvidence(), "Explain a concept", 8192, { _, _ -> session(interrupted) }, {}, checkpoint = checkpoint)
            .streamRound(emptyList(), emptyList()).toList()
        val failure = result.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("CONNECTION_ABORTED", failure.errorCode)
        assertEquals("invocation-17", failure.invocationId)
        assertFalse(failure.transportComplete)
        assertEquals(JsonPrimitive("req-17"), failure.failureDetails?.get("server_request_id"))
        assertEquals(JsonPrimitive("response_stream"), rows.getValue("active")["failed_stage"])
        assertEquals("Retained candidate", checkpoint.load()?.draft)
        assertEquals(null, checkpoint.load()?.blockedReason)
    }

    @Test fun completeMalformedReviewIsNegativeCachedButUnavailableTransportIsNot() = runTest {
        val cache = GitHubReviewCompatibility { 100L }
        var probes = 0
        val failure = ProviderEvent.Failed("Temporary failure", errorCode = "CONNECTION_ABORTED")
        repeat(2) {
            assertTrue(
                cache.check("selected") {
                    probes++
                    GitHubReviewCompatibility.Result.Unavailable(failure)
                } is GitHubReviewCompatibility.Result.Unavailable
            )
        }
        assertEquals(2, probes)
        assertEquals(
            GitHubReviewCompatibility.Result.UnsupportedFormat,
            cache.check("selected") {
                probes++
                GitHubReviewCompatibility.Result.UnsupportedFormat
            }
        )
        assertEquals(GitHubReviewCompatibility.Result.UnsupportedFormat, cache.check("selected") { error("Negative cache must reuse the observed malformed contract") })
        assertEquals(3, probes)
    }

    @Test fun completedOutputLimitedPreflightGetsOneCompactSameProfileProbeBeforeWork() = runTest {
        var probes = 0
        var work = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                work++
                return flowOf(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())), ProviderEvent.Completed)
            }
        }
        val notices = mutableListOf<String>()
        val result = GitHubReviewedSession(delegate, GitHubReviewEvidence(), "Inspect the repository", 8192, { prompt, kind ->
            assertEquals("github-review-preflight", kind)
            assertEquals(0, work)
            probes++
            if (probes == 1) {
                assertEquals(GitHubReviewCompatibility.BOUNDED_RETRY_PROMPT, prompt)
                session(ProviderEvent.TextDelta("{\"schema_version\":"), ProviderEvent.Usage(outputTokens = 2048, finishReason = "length"), ProviderEvent.Completed)
            } else {
                assertEquals(GitHubReviewCompatibility.BOUNDED_RETRY_PROMPT, prompt)
                session(ProviderEvent.TextDelta(verdict(listOf(GitHubReviewCompatibility.EVIDENCE_ID))), ProviderEvent.Completed)
            }
        }, { notices += it }, compatibility = GitHubReviewCompatibility(), reviewerRevision = "same-selected-profile").streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, probes)
        assertEquals(1, work)
        assertTrue(notices.any { "once" in it })
        assertTrue(result.any { it is ProviderEvent.ToolCall })
        assertFalse(result.any { it is ProviderEvent.Failed })
    }

    @Test fun exhaustedCompactProbeStaysBlockedAfterTwoCallsWithoutFalseCompatibility() = runTest {
        var probes = 0
        val compatibility = GitHubReviewCompatibility()
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Repository work must remain blocked")
        }
        val result = GitHubReviewedSession(delegate, GitHubReviewEvidence(), "Inspect the repository", 8192, { _, _ ->
            probes++
            session(ProviderEvent.Failed("Bounded answer repair ended", errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED", invocationId = "probe-$probes"))
        }, {}, compatibility = compatibility, reviewerRevision = "selected").streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, probes)
        val failure = result.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("COMPLETION_REPAIR_BUDGET_EXHAUSTED", failure.errorCode)
        assertEquals("probe-2", failure.invocationId)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("repository_operations_started"))
        var rechecked = false
        assertEquals(
            GitHubReviewCompatibility.Result.Supported,
            compatibility.check("selected") {
                rechecked = true
                GitHubReviewCompatibility.Result.Supported
            }
        )
        assertTrue(rechecked)
    }

    @Test fun truncatedButInterruptedProbeCannotTriggerCompactFallback() = runTest {
        var probes = 0
        val result = GitHubReviewedSession(session(ProviderEvent.Completed), GitHubReviewEvidence(), "Inspect the repository", 8192, { _, _ ->
            probes++
            session(ProviderEvent.Usage(outputTokens = 2048, finishReason = "length"), ProviderEvent.Failed("Connection interrupted", errorCode = "CONNECTION_ABORTED"))
        }, {}, compatibility = GitHubReviewCompatibility()).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, probes)
        assertEquals("CONNECTION_ABORTED", result.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun compactFirstProbePreservesRateLimitAndNeverDispatchesAnotherAttemptOrRepositoryWork() = runTest {
        var probes = 0
        val limited = ProviderEvent.Failed(
            "Reviewer rate limited",
            errorCode = "RATE_LIMITED",
            invocationId = "rate-limited-probe",
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_generation")
                put("retry_after_ms", 45000)
                put("http_status", 429)
                put("dispatched", true)
            }
        )
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Rate limiting cannot authorize repository work")
        }
        val result = GitHubReviewedSession(delegate, GitHubReviewEvidence(), "Inspect the repository", 8192, { prompt, kind ->
            probes++
            assertEquals(GitHubReviewCompatibility.BOUNDED_RETRY_PROMPT, prompt)
            assertEquals("github-review-preflight", kind)
            session(limited)
        }, {}, compatibility = GitHubReviewCompatibility(), reviewerRevision = "same-selected-profile").streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, probes)
        val failure = result.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("RATE_LIMITED", failure.errorCode)
        assertEquals("rate-limited-probe", failure.invocationId)
        assertEquals(JsonPrimitive(45000), failure.failureDetails?.get("retry_after_ms"))
        assertEquals(JsonPrimitive(429), failure.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("repository_operations_started"))
    }
}
