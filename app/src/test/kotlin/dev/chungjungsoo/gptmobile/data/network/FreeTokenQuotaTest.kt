package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionUsage
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeTokenQuotaTest {
    @Test fun tokenOnlyBucketRestoresAfterHourlyRequestWindowExpires() {
        var now = 1_000_000L
        val file = Files.createTempDirectory("token-only-quota").resolve("quota.json").toFile()
        try {
            val first = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            val held = first.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 40_000, 50_000)
            now += 3_600_001
            first.settle(held, 50_000)
            val restarted = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            assertEquals(50_000L, restarted.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
            assertThrows(FreeAiRateLimitException::class.java) { restarted.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 50_000, 1) }
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun dailyAllowanceSurvivesRestartAndSharesOneCredentialAcrossModels() {
        var now = 1_000_000L
        val file = Files.createTempDirectory("token-quota").resolve("quota.json").toFile()
        try {
            val first = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            first.reserveAttempt(FreeAiProvider.LLM7, "a", "same-key", 90_000, 10_000)
            now += 1000
            val second = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            assertEquals(100_000L, second.reservedTokens(FreeAiProvider.LLM7, "b", "same-key"))
            val failure = assertThrows(FreeAiRateLimitException::class.java) { second.reserveAttempt(FreeAiProvider.LLM7, "b", "same-key", 1, 1) }
            assertEquals("daily_tokens", failure.cooldown?.category)
            assertEquals(87_400_000L, failure.cooldown?.retryAt)
            second.reserveAttempt(FreeAiProvider.LLM7, "a", "other-key", 1, 1)
            now = 87_400_000L
            second.reserveAttempt(FreeAiProvider.LLM7, "a", "same-key", 1, 1)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun actualUsageReleasesUnusedOutputButUnknownOutcomeKeepsItsReservation() {
        var now = 1_000_000L
        val limiter = FreeAiRequestLimiter { now }
        val first = limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 20_000, 30_000)
        limiter.settle(first, 25_000)
        limiter.settle(first, 1) // Replayed callback cannot refund again.
        assertEquals(25_000L, limiter.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
        now += 1000
        val uncertain = limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 20_000, 30_000)
        limiter.settle(uncertain, null)
        assertEquals(75_000L, limiter.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
        now += 1000
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 20_000, 6000) }
    }

    @Test fun aProvenLocalFailureRefundsTokensAndRequestCountOnlyOnce() {
        val limiter = FreeAiRequestLimiter { 1_000_000L }
        val reserved = limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 50_000, 50_000)
        limiter.settle(reserved, null, definitelyNotTransmitted = true)
        limiter.settle(reserved, null, definitelyNotTransmitted = true)
        assertEquals(0L, limiter.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
        limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 1, 1)
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 1, 1) }
    }

    @Test fun forwardClockJumpDoesNotExpireLiveDailyReservationsEarly() {
        var wall = 1_000_000L
        var elapsed = 1_000L
        val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall })
        limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 50_000, 50_000)
        wall += 86_400_000
        assertEquals(wall + 86_400_000, limiter.cooldownUntilMillis(FreeAiProvider.LLM7, "b", "key"))
        elapsed += 86_400_000
        assertNull(limiter.cooldownUntilMillis(FreeAiProvider.LLM7, "b", "key"))
    }

    @Test fun rejectedPreparationNeverChargesARequest() = runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        runCatching { limiter.withPermit(FreeAiProvider.LLM7, "a", "key") { error("context rejected") } }
        limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 1, 1)
        assertEquals(2L, limiter.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
    }

    @Test fun corruptQuotaStorageCannotSilentlyResetAllowance() {
        val file = Files.createTempFile("corrupt-quota", ".json").toFile()
        try {
            file.writeText("not-json")
            val limiter = FreeAiRequestLimiter().also { it.initialize(file) }
            repeat(2) { assertThrows(IllegalStateException::class.java) { limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 1, 1) } }
        } finally {
            file.delete()
        }
    }

    @Test fun aFailedReservationWriteDoesNotConsumeQuotaWhenStorageRecovers() {
        val directory = Files.createTempDirectory("quota-write-failure").toFile()
        val parent = java.io.File(directory, "blocked").also { it.writeText("not a directory") }
        val file = java.io.File(parent, "state.json")
        try {
            val limiter = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            assertThrows(IllegalStateException::class.java) { limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 50_000, 50_000) }
            assertEquals(0L, limiter.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
            parent.delete()
            parent.mkdirs()
            limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 50_000, 50_000)
            val restarted = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            assertEquals(100_000L, restarted.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aFailedSettlementWriteCanBeRetriedWithoutLosingTheReservation() {
        val directory = Files.createTempDirectory("quota-settlement-failure").toFile()
        val file = java.io.File(directory, "state.json")
        try {
            val limiter = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            val reserved = limiter.reserveAttempt(FreeAiProvider.LLM7, "a", "key", 50_000, 50_000)
            val temporary = java.io.File(file.path + ".tmp").also { it.mkdirs() }
            assertThrows(IllegalStateException::class.java) { limiter.settle(reserved, 10_000) }
            assertEquals(100_000L, limiter.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
            temporary.delete()
            limiter.settle(reserved, 10_000)
            val restarted = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            assertEquals(10_000L, restarted.reservedTokens(FreeAiProvider.LLM7, "a", "key"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun interruptedOrStaleUsageCannotRefundUnreportedTokens() {
        val interrupted = FreeTokenUsageReceipt()
        interrupted.observe(ChatCompletionUsage(10, 5, 15), hasPayload = true)
        assertNull(interrupted.settledTokens())

        val stale = FreeTokenUsageReceipt()
        stale.observe(ChatCompletionUsage(10, 5, 15), hasPayload = true)
        stale.observe(null, hasPayload = true)
        stale.completed(true)
        assertNull(stale.settledTokens())

        val complete = FreeTokenUsageReceipt()
        complete.observe(ChatCompletionUsage(10, 5, 15), hasPayload = true)
        complete.observe(ChatCompletionUsage(10, 5, 15), hasPayload = false)
        complete.completed(true)
        assertEquals(15L, complete.settledTokens())

        val regressed = FreeTokenUsageReceipt()
        regressed.observe(ChatCompletionUsage(totalTokens = 30), hasPayload = true)
        regressed.observe(ChatCompletionUsage(totalTokens = 20), hasPayload = false)
        regressed.completed(true)
        assertNull(regressed.settledTokens())
    }

    @Test fun providerUsageMustBeNonnegativeAndInternallyConsistent() {
        assertEquals(15L, ChatCompletionUsage(10, 5, 15).trustedTotalTokens())
        assertEquals(15L, ChatCompletionUsage(totalTokens = 15).trustedTotalTokens())
        assertNull(ChatCompletionUsage(10, 5, 10).trustedTotalTokens())
        assertNull(ChatCompletionUsage(-1, 5, 4).trustedTotalTokens())
        assertNull(ChatCompletionUsage(0, 0, 0).trustedTotalTokens())
    }
}
