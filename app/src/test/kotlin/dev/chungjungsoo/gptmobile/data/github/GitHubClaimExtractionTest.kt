package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.ProviderHttpFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClaimExtractionTest {
    private val request = GitHubClaimBatches.Request("{}", emptySet())
    private val response = """{"claims":[{"kind":"not_measured","subject":"Remaining acceptance"}]}"""

    private fun session(events: Flow<ProviderEvent>) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            return events
        }
    }

    private suspend fun collect(events: Flow<ProviderEvent>) = GitHubClaimExtraction.collect(request, { session(events) }, Dispatchers.Unconfined)

    private suspend fun failed(events: Flow<ProviderEvent>): GitHubClaimExtraction.Failure = try {
        collect(events)
        throw AssertionError("Expected extraction failure")
    } catch (failure: GitHubClaimExtraction.Failure) {
        failure
    }

    @Test fun completeSchemaValidOutputSurvivesEverySupportedLengthFinish() = runBlocking {
        for (finish in listOf("length", "max_tokens", "max_output_tokens", "token_limit")) {
            val result = collect(flowOf(ProviderEvent.TextDelta(response), ProviderEvent.Usage(finishReason = finish), ProviderEvent.Completed))
            assertEquals((Json.parseToJsonElement(response) as JsonObject)["claims"], result["claims"])
        }
    }

    @Test fun completedProviderOutputLimitCanUseValidatedOutputOrRequestSmallerBatch() = runBlocking {
        for (code in listOf("OUTPUT_LIMIT", "COMPLETION_REPAIR_BUDGET_EXHAUSTED")) {
            val failure = ProviderEvent.Failed("Output allowance reached", errorCode = code, transportComplete = true, invocationId = "owned-attempt")
            assertTrue(collect(flowOf(ProviderEvent.TextDelta(response), failure))["claims"] != null)
            val truncated = failed(flowOf(ProviderEvent.TextDelta("{\"claims\":["), failure))
            assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", truncated.code)
            assertTrue(truncated.repairAllowed)
            assertSame(failure, truncated.providerFailure)
        }
    }

    @Test fun unknownOutcomeCannotBecomeSchemaRepairEvenAfterLaterOutputLimit() = runBlocking {
        val original = ProviderEvent.Failed(
            "Inspect the saved job",
            errorCode = "GATEWAY_OUTCOME_UNKNOWN",
            failureDetails = buildJsonObject { put("requires_reconciliation", true) },
            invocationId = "unknown-attempt"
        )
        val result = failed(
            flowOf(original, ProviderEvent.TextDelta(response), ProviderEvent.Failed("Limit", errorCode = "OUTPUT_LIMIT", transportComplete = true), ProviderEvent.Completed)
        )
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", result.code)
        assertSame(original, result.providerFailure)
    }

    @Test fun incompleteOutputLimitCannotAuthorizeAnotherExtractionAttempt() = runBlocking {
        val original = ProviderEvent.Failed("Output allowance reached", errorCode = "OUTPUT_LIMIT", invocationId = "incomplete-attempt")
        val result = failed(flowOf(ProviderEvent.TextDelta(response), original, ProviderEvent.Completed))
        assertEquals("OUTPUT_LIMIT", result.code)
        assertSame(original, result.providerFailure)
    }

    @Test fun rateLimitCannotBeOverwrittenByALaterCompletedOutputLimit() = runBlocking {
        val original = ProviderEvent.Failed("HTTP 429", errorCode = "429", invocationId = "rejected-attempt")
        val result = failed(
            flowOf(original, ProviderEvent.TextDelta(response), ProviderEvent.Failed("Limit", errorCode = "OUTPUT_LIMIT", transportComplete = true), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
        )
        assertEquals("429", result.code)
        assertSame(original, result.providerFailure)
    }

    @Test fun unknownAcceptanceAndExplicitReplayProhibitionsCannotBecomeLimitRecovery() = runBlocking {
        val conditions = listOf(
            buildJsonObject { put("remote_acceptance", "UNKNOWN") },
            buildJsonObject { put("retryable", false) },
            buildJsonObject { put("request_replay_allowed", false) },
            buildJsonObject { put("logical_continuation_allowed", false) },
            buildJsonObject { put("automatic_retry_allowed", false) }
        )
        for (details in conditions) {
            val original = ProviderEvent.Failed("Limit", errorCode = "OUTPUT_LIMIT", transportComplete = true, failureDetails = details)
            val result = failed(flowOf(ProviderEvent.TextDelta(response), original, ProviderEvent.Completed))
            assertEquals("OUTPUT_LIMIT", result.code)
            assertSame(original, result.providerFailure)
            assertFalse(result.repairAllowed)
        }
    }

    @Test fun laterFailuresCanOnlyTightenCompletedLimitRepair() = runBlocking {
        val first = ProviderEvent.Failed("Limit", errorCode = "OUTPUT_LIMIT", transportComplete = true, invocationId = "first-attempt")
        val laterFailures = listOf(
            first.copy(failureDetails = buildJsonObject { put("retryable", false) }),
            first.copy(failureDetails = buildJsonObject { put("request_replay_allowed", false) }),
            first.copy(failureDetails = buildJsonObject { put("automatic_retry_allowed", false) }),
            first.copy(transportComplete = false),
            ProviderEvent.Failed("HTTP 429", errorCode = "429")
        )
        for (later in laterFailures) {
            val result = failed(flowOf(first, ProviderEvent.TextDelta(response), later, ProviderEvent.Completed))
            assertSame(first, result.providerFailure)
            assertFalse(result.repairAllowed)
        }
    }

    @Test fun thrownHttpRateLimitRetainsRetryTimeAndIsNotMalformedJson() = runBlocking {
        val result = failed(flow { throw ProviderHttpFailure(429, retryAfterMs = 30_156, knownNotAccepted = true) })
        assertEquals("429", result.code)
        assertEquals(JsonPrimitive(429), result.providerFailure?.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(30_156), result.providerFailure?.failureDetails?.get("retry_after_ms"))
        assertEquals(JsonPrimitive("model_invocation"), result.providerFailure?.failureDetails?.get("failed_stage"))
    }

    @Test fun thrownTransportFailurePreservesCauseWithoutPublishingSensitiveMessage() = runBlocking {
        val result = failed(
            flow {
                emit(ProviderEvent.TextDelta("{\"claims\":["))
                throw IOException("connection reset at https://private.example/token=secret")
            }
        )
        assertEquals("CONNECTION_ABORTED", result.code)
        assertFalse(result.providerFailure!!.message.contains("private.example"))
        assertTrue(result.providerFailure.failureDetails.toString().contains("java.io.IOException"))
        assertFalse(result.providerFailure.failureDetails.toString().contains("token=secret"))
    }

    @Test fun failedEventIdentitySurvivesFollowupStreamException() = runBlocking {
        val original = ProviderEvent.Failed("Provider failed", errorCode = "PROVIDER_UNAVAILABLE", invocationId = "owned-attempt")
        val result = failed(
            flow {
                emit(original)
                throw IOException("connection reset")
            }
        )
        assertEquals("PROVIDER_UNAVAILABLE", result.code)
        assertSame(original, result.providerFailure)
    }

    @Test fun unexpectedProviderExceptionCannotEnterFormatRepair() = runBlocking {
        val result = failed(flow { error("Provider implementation failed") })
        assertEquals("GITHUB_EXTRACTION_FAILED", result.code)
        assertTrue(result.providerFailure!!.failureDetails.toString().contains("java.lang.IllegalStateException"))
    }

    @Test fun completeMalformedJsonAndTruncatedLengthRemainDifferentFailures() = runBlocking {
        assertEquals("GITHUB_EXTRACTION_INVALID_RESPONSE", failed(flowOf(ProviderEvent.TextDelta("not JSON"), ProviderEvent.Completed)).code)
        assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", failed(flowOf(ProviderEvent.TextDelta("{\"claims\":["), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)).code)
        assertEquals("GITHUB_EXTRACTION_FAILED", failed(flowOf(ProviderEvent.TextDelta(response))).code)
    }

    @Test fun oversizedStreamIsDrainedBeforeItsCompletedLimitAllowsRepair() = runBlocking {
        var terminalObserved = false
        val result = failed(
            flow {
                emit(ProviderEvent.TextDelta("x".repeat(32_769)))
                emit(ProviderEvent.TextDelta("y".repeat(40_000)))
                terminalObserved = true
                emit(ProviderEvent.Completed)
            }
        )
        assertTrue(terminalObserved)
        assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", result.code)
        val interrupted = failed(
            flow {
                emit(ProviderEvent.TextDelta("x".repeat(32_769)))
                throw IOException("connection reset")
            }
        )
        assertEquals("CONNECTION_ABORTED", interrupted.code)
    }

    @Test fun deadlineRetainsTheFirstRecordedFailureEvenAfterLaterCompletionFrames() = runTest {
        val attempt = GitHubClaimExtraction.Attempt()
        val original = ProviderEvent.Failed("HTTP 429", errorCode = "429", transportComplete = true, invocationId = "original-extraction")
        val result = withTimeoutOrNull(100) {
            GitHubClaimExtraction.collect(
                request,
                {
                    session(
                        flow {
                            emit(original)
                            emit(ProviderEvent.Failed("Later limit", errorCode = "OUTPUT_LIMIT", transportComplete = true))
                            emit(ProviderEvent.Completed)
                            awaitCancellation()
                        }
                    )
                },
                Dispatchers.Unconfined,
                attempt
            )
        }
        assertEquals(null, result)
        assertSame(original, attempt.failureAtDeadline())
    }

    @Test fun coroutineCancellationRemainsCancellation() = runBlocking {
        val cancelled = CancellationException("caller stopped")
        try {
            collect(flow { throw cancelled })
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun forbiddenToolFrameCannotBecomeAFormatRetry() = runBlocking {
        val result = failed(flowOf(ProviderEvent.ToolCall("write", "github_write", JsonObject(emptyMap())), ProviderEvent.Completed))
        assertEquals("TOOL_PROTOCOL_INVALID_FRAME", result.code)
        assertFalse(result.repairAllowed)
        val original = ProviderEvent.Failed("Inspect the saved job", errorCode = "GATEWAY_OUTCOME_UNKNOWN", failureDetails = buildJsonObject { put("remote_acceptance", "UNKNOWN") })
        val unknown = failed(flowOf(original, ProviderEvent.ToolCall("write", "github_write", JsonObject(emptyMap()))))
        assertSame(original, unknown.providerFailure)
        assertFalse(unknown.repairAllowed)
    }
}
