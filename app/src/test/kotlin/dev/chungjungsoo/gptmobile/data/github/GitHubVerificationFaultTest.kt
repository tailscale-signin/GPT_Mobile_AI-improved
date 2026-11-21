package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerificationFaultTest {
    @Test fun evidenceAdvanceBeforeFallbackDeliveryKeepsFaultAndNeverRetriesTheBrokenRenderer() = runTest {
        val book = spyk(GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), now = { 1000L }))
        suspend fun observe(id: String) = book.observe(
            ProviderEvent.ToolCall(id, "github_context", buildJsonObject { put("action", "status") }),
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("branch", "main") }), false)
        )
        observe("first")
        var rendererAttempts = 0
        coEvery { book.retainAnswerIfCurrent(any(), any(), any(), any()) } coAnswers {
            observe("arrived-before-delivery")
            false
        }
        val session = GitHubVerifiedSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("Private candidate"), ProviderEvent.Completed)
            },
            book,
            "Explain the observed repository branch",
            {
                rendererAttempts++
                throw ClassCastException("private fault")
            },
            { error("The broken renderer cannot invoke a model") },
            {},
            {},
            now = { 1000L }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val failure = events.last() as ProviderEvent.Failed
        assertEquals(1, rendererAttempts)
        assertEquals("GITHUB_VERIFICATION_INTERNAL_ERROR", failure.errorCode)
        assertEquals(JsonPrimitive("GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION"), failure.failureDetails?.get("checkpoint_failure_code"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("response_delivered"))
        assertEquals(JsonPrimitive(1), failure.failureDetails?.get("verification_receipts_started"))
        assertEquals(JsonPrimitive(2), failure.failureDetails?.get("verification_receipts_observed"))
        assertFalse(events.any { it is ProviderEvent.Completed || it is ProviderEvent.TextDelta })
        assertEquals(2, book.snapshot().first.size)
    }

    @Test fun emergencyFindingsReadBoundedPrimitiveFactsAndTolerateMalformedNumericAndMissingFields() {
        val receipts = linkedMapOf<String, JsonObject>()
        for (index in 1..20) {
            receipts["receipt-$index"] = buildJsonObject {
                put("trusted", true)
                put("success", true)
                put("tool", "github_issue")
                put("source_head_sha", "a".repeat(40))
                put(
                    "result",
                    buildJsonObject {
                        put("number", if (index % 2 == 0) JsonPrimitive(Long.MAX_VALUE) else JsonPrimitive("malformed"))
                        put("title", "Captured issue $index")
                        put("state", "open")
                        put("body", "Private whole body should not be copied into the emergency report".repeat(100))
                    }
                )
            }
        }
        receipts["untrusted"] = buildJsonObject {
            put("trusted", false)
            put("success", true)
            put("result", buildJsonObject { put("title", "Excluded untrusted finding") })
        }
        receipts["missing"] = buildJsonObject {
            put("trusted", true)
            put("success", true)
        }
        val fallback = GitHubVerificationFault.partial(ClassCastException("private crash"), GitHubVerificationFault.Phase.RECEIPT_RENDERING, receipts, null)
        assertTrue(fallback.text.contains("Captured issue 20"))
        assertTrue(fallback.text.contains("receipt-20"))
        assertTrue(fallback.text.contains("captured_sha"))
        assertFalse(fallback.text.contains("malformed"))
        assertFalse(fallback.text.contains("Private whole body"))
        assertFalse(fallback.text.contains("Excluded untrusted finding"))
        assertEquals(8, (fallback.report["retained_observations"] as kotlinx.serialization.json.JsonArray).size)
        assertFalse(GitHubExecutionContinuation.canResume(fallback.report, receipts, 200, 300_000, allowVerificationResume = true))
    }

    @Test fun formatterTypeFaultPublishesSavedObservationsAndStopsWithoutAnotherProviderOrToolRequest() = runTest {
        val evidence = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), now = { 1000L })
        evidence.observe(
            ProviderEvent.ToolCall("saved-read", "github_context", buildJsonObject { put("action", "status") }),
            AgentToolResult("saved-read", ToolResultContent.Json(buildJsonObject { put("branch", "main") }), false)
        )
        var publications: JsonObject? = null
        var verificationPlans = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
                flowOf(ProviderEvent.TextDelta("A private candidate."), ProviderEvent.Completed)
        }
        val session = GitHubVerifiedSession(
            delegate, evidence, "Explain the observed repository branch",
            {
                verificationPlans++
                throw ClassCastException("private payload: Long cannot be cast to Integer")
            },
            { error("A failed planner must not invoke a verification model") }, {}, {}, now = { 1000L },
            onPublication = { publications = it },
            openExecutionContinuation = { error("An internal verifier fault cannot repeat repository work") }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, verificationPlans)
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals(GitHubVerificationFault.CODE, terminal.errorCode)
        assertEquals(JsonPrimitive("CLAIM_PLANNING"), terminal.failureDetails?.get("verification_phase"))
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("request_replay_allowed"))
        assertTrue(terminal.failureDetails?.get("exception_fingerprint") != null)
        assertTrue(terminal.failureDetails?.get("exception_application_frames") != null)
        assertEquals(JsonPrimitive(true), publications?.get("response_delivered"))
        assertEquals(JsonPrimitive(false), publications?.get("completion_verified"))
        assertEquals(JsonPrimitive(false), publications?.get("continuation_required"))
        assertEquals(1, evidence.snapshot().first.size)
        val visible = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(visible.contains("1 receipt observations"))
        assertFalse(visible.contains("private payload"))
        assertFalse(visible.contains("A private candidate"))
        assertFalse(events.any { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
    }

    @Test fun verificationFaultKeepsTheOriginalFailurePrimaryAndDoesNotClaimUnknownWorkSucceeded() {
        val original = ProviderEvent.Failed(
            "Original interruption",
            errorCode = "GATEWAY_OUTCOME_UNKNOWN",
            failureDetails = buildJsonObject {
                put("requires_reconciliation", true)
                put("remote_acceptance", "UNKNOWN")
                put("request_replay_allowed", false)
            }
        )
        val fallback = GitHubVerificationFault.partial(
            ClassCastException("private exception text"),
            GitHubVerificationFault.Phase.ACCEPTANCE_RENDERING,
            mapOf("uncertain" to buildJsonObject { put("dispatched", true) }),
            original
        )
        assertEquals(original.errorCode, fallback.failure.errorCode)
        assertEquals(JsonPrimitive(true), fallback.failure.failureDetails?.get("requires_reconciliation"))
        assertEquals(JsonPrimitive("UNKNOWN"), fallback.failure.failureDetails?.get("remote_acceptance"))
        assertTrue(fallback.failure.failureDetails?.get("verification_failure") is JsonObject)
        assertFalse(fallback.report.toString().contains("private exception text"))
        assertTrue(fallback.text.contains("0 report success"))
        assertFalse(fallback.report.containsKey("verified_criteria"))
    }
}
