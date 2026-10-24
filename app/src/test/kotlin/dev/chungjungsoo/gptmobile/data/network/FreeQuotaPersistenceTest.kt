package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
        wall += 86_400_000
        assertEquals(wall + 120_000, limiter.savedCooldown(FreeAiProvider.KILO)?.retryAt)
        limiter.defer(FreeAiProvider.KILO, "10")
        assertEquals(wall + 120_000, limiter.savedCooldown(FreeAiProvider.KILO)?.retryAt)
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.KILO) }
        elapsed += 120_000
        assertNull(limiter.savedCooldown(FreeAiProvider.KILO))
    }
}
