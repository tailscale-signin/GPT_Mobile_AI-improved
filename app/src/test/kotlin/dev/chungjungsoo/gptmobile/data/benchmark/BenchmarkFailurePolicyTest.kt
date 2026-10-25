package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchmarkFailurePolicyTest {
    @Test fun typedRetryDeadlineWinsOverProseAndSurvivesCauseWrapping() {
        val deadline = Instant.parse("2026-10-25T02:47:00.750Z").toEpochMilli()
        val decision = ProviderLimitDecision("LLM7", "profile", "model", deadline, "daily", "PROVIDER_RATE_LIMITED", true)
        val error = BenchmarkProviderFailureException("Daily quota exhausted.", "PROVIDER_RATE_LIMITED", decision)
        val failure = benchmarkFailure(IllegalStateException("Request failed", error))
        assertEquals(deadline, failure.nextEligibleAt)
        assertEquals("QUOTA_BLOCKED", failure.code)
        assertEquals(BenchmarkOutcome.QUOTA_BLOCKED, failure.outcome())
    }

    @Test fun legacyTimestampIgnoresTrailingSentencePunctuation() {
        val timestamp = "2026-10-25T02:47:00.750Z"
        assertEquals(Instant.parse(timestamp).toEpochMilli(), retryTimestampFromLegacyMessage("Next eligible retry: $timestamp. Choose another provider."))
        assertNull(retryTimestampFromLegacyMessage("Next eligible retry: tomorrow."))
    }

    @Test fun pendingJobsAndLocalLimitsKeepTheirOwnOutcomes() {
        val pending = benchmarkFailure(BenchmarkProviderFailureException("Receipt saved", "QUEUE_JOB_PENDING"))
        assertEquals("REMOTE_JOB_PENDING", pending.code)
        assertEquals(BenchmarkOutcome.PARTIAL, pending.outcome())
        val local = benchmarkFailure(BenchmarkProviderFailureException("Local request allowance exhausted", "LOCAL_ALLOWANCE_EXHAUSTED"))
        assertEquals("LOCAL_ALLOWANCE_BLOCKED", local.code)
        assertEquals(BenchmarkOutcome.QUOTA_BLOCKED, local.outcome())
        assertEquals("COOLDOWN_ACTIVE", benchmarkFailure(BenchmarkProviderFailureException("Saved server cooldown", "LOCAL_COOLDOWN_ACTIVE")).code)
    }

    @Test fun fixedProviderCeilingIsRejectedBeforeTheSmallerBenchmarkCanDispatch() {
        val profile = PlatformV2(name = "Pollinations", compatibleType = ClientType.FREE, apiUrl = FreeAiProvider.POLLINATIONS.apiUrl)
        val error = assertThrows(BenchmarkUnsupportedConfigurationException::class.java) { requireBenchmarkOutputContract(profile, "speed", 768) }
        val failure = benchmarkFailure(error)
        assertEquals(BenchmarkOutcome.UNSUPPORTED, failure.outcome())
        assertEquals("admission", failure.stage)
        assertFalse(failure.retryable)
        requireBenchmarkOutputContract(profile, "task", 2048)
        assertEquals(768, benchmarkCompletionBudget(profile, "speed"))
    }
}
