package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BenchmarkProviderOutcomeTest {
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
    }

    private fun test() = BenchmarkCase("fixture", "Fixture", "task", "Reply READY", "READY")
    private fun runner(vararg events: ProviderEvent) = BenchmarkRunner({ _, _ ->
        object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events)
        }
    })
}
