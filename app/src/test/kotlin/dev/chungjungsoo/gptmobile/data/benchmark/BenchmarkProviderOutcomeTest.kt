package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkProviderOutcomeTest {
    @Test fun reasoningCodeDoesNotInventDispatchEvidenceForEarlierAttempts() = runTest {
        val sample = runner(
            ProviderEvent.Failed(
                "Reasoning control unavailable",
                errorCode = "REASONING_OFF_UNSUPPORTED",
                failureDetails = buildJsonObject { put("attempt_dispatch_state", "NOT_DISPATCHED") }
            )
        ).run(test(), false)
        assertEquals(BenchmarkOutcome.UNSUPPORTED, sample.outcome)
        assertEquals("admission", sample.failure?.stage)
        assertEquals(false, sample.failure?.retryable)
        assertNull(sample.requestDispatched)
        assertNull(sample.requestAdmitted)
        assertNull(sample.validation)
    }

    @Test fun confirmedUnsentRequestRetainsItsAdmissionEvidence() = runTest {
        val sample = runner(
            ProviderEvent.Failed("Reasoning control unavailable", errorCode = "REASONING_OFF_UNSUPPORTED", failureDetails = buildJsonObject { put("dispatched", false) })
        ).run(test(), false)
        assertEquals(false, sample.requestDispatched)
        assertEquals(false, sample.requestAdmitted)
    }

    @Test fun laterAdmissionFailureCannotEraseAnObservedGeneration() = runTest {
        val sample = runner(
            ProviderEvent.TextDelta("Observed partial answer"),
            ProviderEvent.Failed("Reasoning control unavailable", errorCode = "REASONING_OFF_UNSUPPORTED", failureDetails = buildJsonObject { put("dispatched", false) })
        ).run(test(), false)
        assertEquals(true, sample.requestDispatched)
        assertEquals(true, sample.requestAdmitted)
    }

    @Test fun namedPayloadRejectionRetainsHttpStatusAndDoesNotGradeAnAbsentAnswer() = runTest {
        val sample = runner(
            ProviderEvent.Failed("Invalid response format", errorCode = "PROVIDER_PAYLOAD_ERROR", failureDetails = buildJsonObject { put("http_status", 400) })
        ).run(test(), false)
        assertEquals(400, sample.providerHttpStatus)
        assertEquals(false, sample.requestAdmitted)
        assertEquals(true, sample.requestDispatched)
        assertNull(sample.validation)
        assertEquals(false, sample.failure?.retryable)
    }

    @Test fun readinessHttpFailureDoesNotClaimAnInferenceDispatch() = runTest {
        val sample = runner(
            ProviderEvent.Failed(
                "Readiness request failed",
                errorCode = "REASONING_CAPABILITY_UNAVAILABLE",
                failureDetails = buildJsonObject {
                    put("attempt_dispatch_state", "NOT_DISPATCHED")
                    put("http_status", 400)
                }
            )
        ).run(test(), false)
        assertNull(sample.providerHttpStatus)
        assertNull(sample.requestDispatched)
        assertNull(sample.requestAdmitted)
        assertNull(sample.validation)
    }

    @Test fun pendingJobIsPartialWaitingWithItsOwnCategoryAndNoQualityGrade() = runTest {
        val sample = runner(ProviderEvent.Failed("QUEUE_JOB_PENDING: receipt saved", errorCode = "QUEUE_JOB_PENDING"))
            .run(test(), false)
        assertEquals(BenchmarkOutcome.PARTIAL, sample.outcome)
        assertEquals("REMOTE_JOB_PENDING", sample.failureCategory)
        assertFalse(sample.completed)
        assertEquals(BenchmarkRecoveryAction.STOP_PROFILE, sample.recoveryAction())
    }

    @Test fun malformedStreamCannotBecomeAValidThroughputCutoff() = runTest {
        val sample = runner(
            ProviderEvent.TextDelta("Rain forms when water vapour cools and condenses into droplets. ".repeat(5)),
            ProviderEvent.Usage(outputTokens = 768, finishReason = "length"),
            ProviderEvent.Failed("STREAM_DEGRADED: malformed chunk", errorCode = "STREAM_DEGRADED")
        ).run(test().copy(category = "speed"), false)
        assertEquals(BenchmarkOutcome.PARTIAL, sample.outcome)
        assertEquals("STREAM_DEGRADED", sample.failureCategory)
        assertEquals(false, sample.timingEligible)
    }

    @Test fun typedQuotaDeadlineSurvivesAndLocalAllowanceDoesNotClaimADispatch() = runTest {
        val local = ProviderLimitDecision("OVHCLOUD", "profile", "model", 12345L, "minute", "LOCAL_ALLOWANCE_EXHAUSTED", true)
        val localSample = runner(ProviderEvent.Failed("Local allowance", errorCode = local.category, providerLimit = local)).run(test(), false)
        assertEquals(BenchmarkOutcome.QUOTA_BLOCKED, localSample.outcome)
        assertEquals("LOCAL_ALLOWANCE_BLOCKED", localSample.failureCategory)
        assertEquals(12345L, localSample.failure?.nextEligibleAt)
        assertEquals(false, localSample.requestDispatched)
        val server = local.copy(category = "PROVIDER_RATE_LIMITED")
        val serverSample = runner(ProviderEvent.Failed("Daily quota", errorCode = server.category, providerLimit = server)).run(test(), false)
        assertEquals("QUOTA_BLOCKED", serverSample.failureCategory)
        assertEquals(true, serverSample.requestDispatched)
        assertEquals(false, serverSample.requestAdmitted)
        assertFalse(serverSample.knownExecuted())
        val run = BenchmarkRun("run", "profile", "Profile", "FREE", "model", "config", false, BenchmarkMode.QUICK, 1, listOf(serverSample), plannedTrials = 1)
        assertEquals(0, benchmarkCoverage(run).executed)
        assertEquals(0, benchmarkCoverage(run).admitted)
        val metrics = benchmarkMetricResults(listOf(run)).associateBy { it.id }
        assertEquals(1, metrics.getValue("delivery_completion").denominator)
        assertEquals(0.0, metrics.getValue("profile_availability").value!!, .001)
    }

    private fun test() = BenchmarkCase("fixture", "Fixture", "task", "Reply READY", "READY")
    private fun runner(vararg events: ProviderEvent) = BenchmarkRunner({ _, _ ->
        object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events)
        }
    })
}
