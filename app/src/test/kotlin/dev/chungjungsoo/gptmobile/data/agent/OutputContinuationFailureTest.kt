package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.ProviderHttpFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputContinuationFailureTest {
    @Test fun rejectedRestoredContinuationRetainsHttpCauseAndSavedTextWithoutRetry() = runTest {
        var attempts = 0
        val saved = mutableListOf<String>()
        val session = OutputLimitRecoverySession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.Completed)
            },
            maxContinuations = 4,
            initialDraft = "Retained useful answer.",
            effectiveOutputCap = 6144,
            saveProgress = { draft, _, _, _ -> saved += draft }
        ) { _, _ ->
            attempts++
            throw ProviderHttpFailure(413, null, knownNotAccepted = true)
        }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(1, attempts)
        assertEquals("413", failure.errorCode)
        assertEquals(JsonPrimitive(413), failure.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive("HTTP_REJECTED"), failure.failureDetails?.get("continuation_cause"))
        assertEquals(JsonPrimitive(6144), failure.failureDetails?.get("continuation_output_cap"))
        assertEquals(JsonPrimitive(1), failure.failureDetails?.get("continuation_attempt_count"))
        assertEquals(JsonPrimitive("REPLAN_REQUEST_BUDGET"), failure.failureDetails?.get("next_action"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
        assertEquals("Retained useful answer.", saved.last())
        assertFalse(events.any { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
    }

    @Test fun unknownOpenFaultRetainsSafeTypeWithoutInventingHttpOrDispatchFacts() {
        val failure = OutputContinuationFailure.opening(IllegalStateException("private prompt / token"), ContinuationTrigger.PROVIDER_OUTPUT_LIMIT, 20, 1, 1000)
        assertEquals("OUTPUT_CONTINUATION_UNAVAILABLE", failure.errorCode)
        assertEquals(JsonPrimitive("OPEN_FAILED"), failure.failureDetails?.get("continuation_cause"))
        assertFalse(requireNotNull(failure.failureDetails).containsKey("dispatched"))
        assertFalse(requireNotNull(failure.failureDetails).containsKey("http_status"))
        assertFalse(failure.toString().contains("private prompt"))
        assertTrue(failure.failureDetails?.get("exception_fingerprint") != null)
    }

    @Test fun wrappedCancellationPropagatesAndMissingTaskDescribesOnlyItsLocalRefusal() {
        val cancellation = CancellationException("cancelled")
        val caught = runCatching { OutputContinuationFailure.opening(IllegalStateException(cancellation), ContinuationTrigger.RESTORED_DRAFT, 5, 0, null) }.exceptionOrNull()
        assertSame(cancellation, caught)
        val missing = OutputContinuationFailure.unavailable(OutputContinuationFailure.Cause.NO_SAVED_TASK)
        assertEquals(JsonPrimitive("planning"), missing.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive("SEND_ORIGINAL_REQUEST"), missing.failureDetails?.get("next_action"))
        assertEquals(JsonPrimitive(false), missing.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("NO"), missing.failureDetails?.get("remote_acceptance"))
    }
}
