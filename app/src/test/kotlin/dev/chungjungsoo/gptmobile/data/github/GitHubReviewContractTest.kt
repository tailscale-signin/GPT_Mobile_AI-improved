package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewContractTest {
    private fun response(verdict: String = "PASS", ids: List<String> = emptyList()) = buildJsonObject {
        put("schema_version", 1)
        put("verdict", verdict)
        put("reason", "Synthetic status evidence checked.")
        put("reviewed_ids", JsonArray(ids.map(::JsonPrimitive)))
        put("issues", JsonArray(if (verdict == "FAIL") listOf(JsonPrimitive("Correct the status.")) else emptyList()))
        put("verified_claims", JsonArray(listOf(JsonPrimitive("The synthetic check passed."))))
    }.toString()

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun strictVersionedVerdictsDoNotCoerceTypesIgnoreDuplicatesOrInventEvidenceCoverage() {
        assertEquals(GitHubReviewSchema.Outcome.PASS, GitHubReviewSchema.validate(response(ids = listOf("ev:0")), listOf("ev:0")).outcome)
        assertEquals(GitHubReviewSchema.Outcome.INDETERMINATE, GitHubReviewSchema.validate(response("INDETERMINATE"), emptyList()).outcome)
        val valid = response()
        for (invalid in listOf(
            valid.replace("\"schema_version\":1", "\"schema_version\":\"1\""),
            valid.replace("\"schema_version\":1", "\"schema_version\":2"),
            valid.replace("\"verdict\":\"PASS\"", "\"verdict\":\"FAIL\",\"verdict\":\"PASS\""),
            valid.replace("\"verdict\":\"PASS\"", "\"verdict\":true"),
            valid.replace("\"verdict\":\"PASS\"", "\"verdict\":\"approved\""),
            valid.replace("\"issues\":[]", "\"issues\":[\"contradiction\"]"),
            valid + "\n{}"
        )) {
            assertTrue(invalid, runCatching { GitHubReviewSchema.validate(invalid, emptyList()) }.isFailure)
        }
        assertTrue(runCatching { GitHubReviewSchema.validate(response(ids = listOf("other")), listOf("ev:0")) }.isFailure)
        assertTrue(runCatching { GitHubReviewSchema.validate(response(ids = listOf("ev:0", "ev:0")), listOf("ev:0")) }.isFailure)
        assertTrue(runCatching { GitHubReviewSchema.validate(response("FAIL").replace("[\"Correct the status.\"]", "[]"), emptyList()) }.isFailure)
    }

    @Test fun incompatibleSelectedProfileStopsBeforeAnyRepositoryOperationAndDoesNotFallBack() = runTest {
        var work = 0
        var probes = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                work++
                return flowOf(ProviderEvent.Completed)
            }
        }
        val compatibility = GitHubReviewCompatibility { 1000L }
        repeat(2) {
            val events = GitHubReviewedSession(delegate, GitHubReviewEvidence(), "Change the repository", 8192, { prompt, kind ->
                assertEquals("github-review-preflight", kind)
                assertEquals(GitHubReviewCompatibility.BOUNDED_RETRY_PROMPT, prompt)
                probes++
                session(ProviderEvent.TextDelta("unstructured review"), ProviderEvent.Completed)
            }, {}, compatibility = compatibility, reviewerRevision = "selected-profile").streamRound(emptyList(), emptyList()).toList()
            assertEquals("GITHUB_REVIEW_INCOMPATIBLE", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        }
        assertEquals(0, work)
        assertEquals(1, probes)
    }

    @Test fun compatibilityIsCachedOnlyForTheSameSelectedConfigurationAndExpires() = runTest {
        var now = 1000L
        val compatibility = GitHubReviewCompatibility { now }
        var probes = 0
        suspend fun supports(revision: String, accepted: Boolean = true) = compatibility.supports(revision) {
            probes++
            accepted
        }
        assertTrue(supports("selected:v1"))
        assertTrue(supports("selected:v1"))
        assertEquals(1, probes)
        assertFalse(supports("selected:v2", false))
        assertFalse(supports("selected:v2"))
        assertEquals(2, probes)
        now += 30_001
        assertTrue(supports("selected:v2"))
        assertEquals(3, probes)
    }

    @Test fun indeterminateEvidenceIsNotMalformedSyntaxAndNeverTriggersBlindDraftRepair() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("Explain the design", { rows[it] }, { key, row -> rows[key] = row })
        var calls = 0
        val events = GitHubReviewedSession(session(ProviderEvent.TextDelta("Draft"), ProviderEvent.Completed), GitHubReviewEvidence(), "Explain the design", 8192, { _, kind ->
            calls++
            assertEquals("github-review", kind)
            session(ProviderEvent.TextDelta(response("INDETERMINATE")), ProviderEvent.Completed)
        }, {}, checkpoint = checkpoint).streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_INDETERMINATE", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals("Draft", checkpoint.load()?.draft)
        assertEquals(1, calls)
        assertTrue(events.none { it is ProviderEvent.TextDelta })
    }

    @Test fun completedReviewSurvivesFinalVerificationFailureWithoutRepeatingGenerationOrReview() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("Explain the design", { rows[it] }, { key, row -> rows[key] = row })
        var generations = 0
        var reviews = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                generations++
                return flowOf(ProviderEvent.TextDelta("Saved answer"), ProviderEvent.Completed)
            }
        }
        repeat(2) {
            val result = GitHubReviewedSession(delegate, GitHubReviewEvidence(), "Explain the design", 8192, { _, _ ->
                reviews++
                session(ProviderEvent.TextDelta(response()), ProviderEvent.Completed)
            }, {}, checkpoint = checkpoint(), reviewerRevision = "same-selected-profile").streamRound(emptyList(), emptyList()).toList()
            assertEquals("Saved answer", result.filterIsInstance<ProviderEvent.TextDelta>().single().text)
            assertTrue(checkpoint().load()?.reviewed == true)
        }
        assertEquals(1, generations)
        assertEquals(1, reviews)
        checkpoint().completed()
        assertEquals(null, checkpoint().load())
    }

    @Test fun persistedReviewBudgetCannotBeReplenishedByRestartOrRepairingTheDraft() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, row -> rows[key] = row })
        checkpoint().save("draft", emptyList(), reviewerRevision = "selected")
        assertTrue(checkpoint().claimPass(2))
        checkpoint().save("corrected draft", emptyList(), reviewerRevision = "selected")
        assertTrue(checkpoint().claimPass(2))
        checkpoint().save("corrected draft", emptyList(), reviewerRevision = "selected")
        assertFalse(checkpoint().claimPass(2))
        assertEquals(JsonPrimitive(2), Json.parseToJsonElement(rows.getValue("active").toString()).jsonObject["review_passes"])
    }

    @Test fun aCompleteValidatedVerdictAtTheOutputBoundaryIsPreservedButTruncatedJsonIsNot() = runTest {
        for (complete in listOf(true, false)) {
            var reviews = 0
            val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved answer"), ProviderEvent.Completed), GitHubReviewEvidence(), "Explain the design", 8192, { _, _ ->
                reviews++
                session(ProviderEvent.TextDelta(if (complete) response() else response().dropLast(4)), ProviderEvent.Usage(outputTokens = 1536, finishReason = "length"), ProviderEvent.Failed("The response reached its output limit", errorCode = "OUTPUT_LIMIT", transportComplete = true))
            }, {}).streamRound(emptyList(), emptyList()).toList()
            assertEquals(complete, output.any { it is ProviderEvent.TextDelta })
            assertEquals(complete, output.last() == ProviderEvent.Completed)
            assertEquals(1, reviews)
        }
    }

    @Test fun failedResumedReviewCannotResetTheDurablePassBudget() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("Explain the design", { rows[it] }, { key, row -> rows[key] = row })
        checkpoint().save("Retained answer", emptyList(), reviewerRevision = "selected")
        repeat(31) { assertTrue(checkpoint().claimPass(32)) }
        var dispatches = 0
        repeat(3) {
            val events = GitHubReviewedSession(session(ProviderEvent.Completed), GitHubReviewEvidence(), "Explain the design", 8192, { _, _ ->
                dispatches++
                session(ProviderEvent.Failed("Temporarily offline", errorCode = "TRANSPORT_ERROR"))
            }, {}, checkpoint = checkpoint(), reviewerRevision = "selected").streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.any { it is ProviderEvent.Failed })
            assertEquals(JsonPrimitive(32), rows.getValue("active")["review_passes"])
            assertEquals("Retained answer", checkpoint().load()?.draft)
        }
        assertEquals(1, dispatches)
        checkpoint().failed("GITHUB_REVIEW_UNAVAILABLE", blocked = false, details = buildJsonObject { put("review_passes", 0) })
        assertEquals(JsonPrimitive(32), rows.getValue("active")["review_passes"])
        assertFalse(checkpoint().claimPass(32))
    }
}
