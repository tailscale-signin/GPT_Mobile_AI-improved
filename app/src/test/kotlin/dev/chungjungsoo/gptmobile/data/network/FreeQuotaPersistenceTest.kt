package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeQuotaPersistenceTest {
    @Test fun localRollingWindowSurvivesRestartAndExpiresAtTheSameDeadline() {
        var now = 1_000_000L
        val file = Files.createTempDirectory("quota-window").resolve("state.json").toFile()
        try {
            val first = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            repeat(2) { first.reserve(FreeAiProvider.OVHCLOUD, "model") }
            val restarted = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            assertEquals(now + 60_000, restarted.cooldownUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
            assertThrows(FreeAiRateLimitException::class.java) { restarted.reserve(FreeAiProvider.OVHCLOUD, "model") }
            assertNull(restarted.cooldownUntilMillis(FreeAiProvider.OVHCLOUD, "other-model"))
            now += 60_000
            restarted.reserve(FreeAiProvider.OVHCLOUD, "model")
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun serverDateCompensatesForSkewAndWallClockJumpCannotShortenRetryAfter() {
        var wall = 1_000_000L
        var elapsed = 5_000L
        val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall })
        assertEquals(120L, limiter.defer(FreeAiProvider.KILO, "Sat, 24 Oct 2026 12:02:00 GMT", serverDate = "Sat, 24 Oct 2026 12:00:00 GMT"))
        val original = limiter.savedCooldown(FreeAiProvider.KILO)
        wall += 86_400_000
        assertEquals(original, limiter.savedCooldown(FreeAiProvider.KILO))
        assertEquals(wall + 120_000, limiter.cooldownUntilMillis(FreeAiProvider.KILO))
        limiter.defer(FreeAiProvider.KILO, "10")
        assertEquals(original, limiter.savedCooldown(FreeAiProvider.KILO))
        assertEquals(wall + 120_000, limiter.cooldownUntilMillis(FreeAiProvider.KILO))
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.KILO) }
        elapsed += 120_000
        assertNull(limiter.savedCooldown(FreeAiProvider.KILO))
    }

    @Test fun clockSamplingDoesNotRewriteProviderEvidenceAndPersistedWaitSurvivesAClockJump() {
        var wall = 1_000_000L
        var elapsed = 5_000L
        val file = Files.createTempDirectory("stable-cooldown").resolve("state.json").toFile()
        try {
            val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall }).also { it.initialize(file) }
            limiter.defer(FreeAiProvider.KILO, "120")
            val original = limiter.savedCooldown(FreeAiProvider.KILO)
            repeat(5) {
                wall++ // The two independently sampled clocks can straddle a millisecond.
                assertEquals(original, limiter.savedCooldown(FreeAiProvider.KILO))
                elapsed++
                assertEquals(original, limiter.savedCooldown(FreeAiProvider.KILO))
            }
            wall += 86_400_000
            limiter.reserve(FreeAiProvider.BLOCKRUN) // Persist all live waits with their effective deadline.
            val restarted = FreeAiRequestLimiter { wall }.also { it.initialize(file) }
            assertEquals(original, restarted.savedCooldown(FreeAiProvider.KILO))
            assertEquals(wall + 119_995, restarted.cooldownUntilMillis(FreeAiProvider.KILO))
            assertThrows(FreeAiRateLimitException::class.java) { restarted.reserve(FreeAiProvider.KILO) }
            wall += 119_995
            assertNull(restarted.savedCooldown(FreeAiProvider.KILO))
            restarted.reserve(FreeAiProvider.KILO)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun legacyCredentialScopesMigrateWithoutResettingTheirAllowance() {
        val file = Files.createTempDirectory("legacy-quota-scope").resolve("state.json").toFile()
        val digest = "a".repeat(64)
        try {
            file.writeText("""{"LLM7|credential:$digest":{"cooldown":{"retryAt":2000000,"credentialIdentity":"$digest","quotaScope":"token"}}}""")
            val limiter = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            val identity = FreeQuotaIdentity.protectDigest(digest)
            assertEquals(2_000_000L, limiter.savedCooldown(FreeAiProvider.LLM7, credentialIdentity = identity)?.retryAt)
            assertThrows(FreeAiRateLimitException::class.java) { limiter.reserveAttempt(FreeAiProvider.LLM7, "model", identity, 1, 1) }
            limiter.reserve(FreeAiProvider.BLOCKRUN)
            assertFalse(file.readText().contains(digest))
            val restarted = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            assertEquals(2_000_000L, restarted.savedCooldown(FreeAiProvider.LLM7, credentialIdentity = identity)?.retryAt)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun forwardClockJumpCannotExpireLocalRequestWindowsAndTheirPersistedProjection() {
        var wall = 1_000_000L
        var elapsed = 5_000L
        val file = Files.createTempDirectory("monotonic-request-window").resolve("state.json").toFile()
        try {
            val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall }).also { it.initialize(file) }
            repeat(2) { limiter.reserve(FreeAiProvider.OVHCLOUD, "model") }
            wall += 86_400_000
            assertEquals(wall + 60_000, limiter.cooldownUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
            assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD, "model") }
            limiter.reserve(FreeAiProvider.BLOCKRUN)
            val restarted = FreeAiRequestLimiter { wall }.also { it.initialize(file) }
            assertEquals(wall + 60_000, restarted.cooldownUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
            elapsed += 60_000
            assertNull(limiter.cooldownUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
            wall += 60_000
            assertNull(restarted.cooldownUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
            limiter.reserve(FreeAiProvider.OVHCLOUD, "model")
            restarted.reserve(FreeAiProvider.OVHCLOUD, "model")
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun forwardClockJumpDoesNotResetPerSecondOrHourlyLimits() {
        var wall = 1_000_000L
        var elapsed = 5_000L
        val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall })
        limiter.reserve(FreeAiProvider.LLM7, "model")
        repeat(200) { limiter.reserve(FreeAiProvider.KILO) }
        wall += 86_400_000
        assertEquals(wall + 1000, limiter.cooldownUntilMillis(FreeAiProvider.LLM7, "model"))
        assertEquals(wall + 3_600_000, limiter.cooldownUntilMillis(FreeAiProvider.KILO))
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.LLM7, "model") }
        elapsed += 1000
        limiter.reserve(FreeAiProvider.LLM7, "model")
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.KILO) }
        elapsed += 3_599_000
        limiter.reserve(FreeAiProvider.KILO)
    }

    @Test fun canonicalWaitScopeFollowsTheObservedAllowanceWithoutRevealingCredentials() {
        val limiter = FreeAiRequestLimiter { 1_000_000L }
        val provider = FreeAiProvider.LLM7
        limiter.defer(provider, "120", "a", "key-a", FreeCooldown(0, quotaScope = "ip"))
        val shared = limiter.activeQuotaScopeKey(provider, "a", "key-a")
        assertEquals(shared, limiter.activeQuotaScopeKey(provider, "b", "key-b"))
        assertFalse(shared.contains("key-a"))
        assertFalse(shared.contains("key-b"))

        val modelLimited = FreeAiRequestLimiter { 1_000_000L }
        modelLimited.defer(provider, "120", "a", "key-a", FreeCooldown(0, quotaScope = "model"))
        assertEquals(modelLimited.activeQuotaScopeKey(provider, "a", "key-a"), modelLimited.activeQuotaScopeKey(provider, "a", "key-b"))
        assertNull(modelLimited.savedCooldown(provider, "b", "key-a"))
        modelLimited.reserveAttempt(provider, "b", "key-a", 1, 1)
        assertThrows(FreeAiRateLimitException::class.java) { modelLimited.reserveAttempt(provider, "a", "key-a", 1, 1) }
    }
}
