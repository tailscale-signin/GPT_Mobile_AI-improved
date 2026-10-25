package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeRequestReceiptTest {
    @Test fun equalTimestampAttemptsRefundOnlyTheirOwnRequestAndOnlyOnce() {
        val file = Files.createTempDirectory("request-receipts").resolve("state.json").toFile()
        try {
            val limiter = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            val provider = FreeAiProvider.OVHCLOUD
            val first = limiter.reserveAttempt(provider, "model", "anonymous", 0, 0)
            val second = limiter.reserveAttempt(provider, "model", "anonymous", 0, 0)
            limiter.settle(first, null)
            limiter.settle(first, null, definitelyNotTransmitted = true)
            assertNotNull(limiter.exhaustedAllowanceUntilMillis(provider, "model", "anonymous"))
            limiter.settle(second, null, definitelyNotTransmitted = true)
            assertNull(limiter.exhaustedAllowanceUntilMillis(provider, "model", "anonymous"))
            limiter.reserveAttempt(provider, "model", "anonymous", 0, 0)
            limiter.settle(second, null, definitelyNotTransmitted = true)
            assertThrows(FreeAiRateLimitException::class.java) { limiter.reserveAttempt(provider, "model", "anonymous", 0, 0) }
            val restarted = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            assertEquals(1_060_000L, restarted.exhaustedAllowanceUntilMillis(provider, "model", "anonymous"))
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun aDefiniteDnsFailureRefundsAnOvhRequestButAmbiguousIoKeepsIt() = runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        limiter.withRequest(FreeAiProvider.OVHCLOUD, "model") { }
        runCatching { limiter.withRequest(FreeAiProvider.OVHCLOUD, "model") { throw java.net.UnknownHostException("fixture.invalid") } }
        assertNull(limiter.exhaustedAllowanceUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
        runCatching { limiter.withRequest(FreeAiProvider.OVHCLOUD, "model") { throw java.io.IOException("response interrupted") } }
        assertEquals(60_000L, limiter.exhaustedAllowanceUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
    }

    @Test fun classicSpacingUsesElapsedTimeAndPersistsTheProjectedDeadline() {
        var wall = 1_000_000L
        var elapsed = 1000L
        val file = Files.createTempDirectory("classic-monotonic").resolve("state.json").toFile()
        try {
            val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall }).also { it.initialize(file) }
            limiter.reserve(FreeAiProvider.POLLINATIONS)
            wall += 86_400_000
            assertNull(limiter.exhaustedAllowanceUntilMillis(FreeAiProvider.POLLINATIONS))
            assertEquals(wall + 30_000, limiter.cooldownUntilMillis(FreeAiProvider.POLLINATIONS))
            limiter.reserve(FreeAiProvider.BLOCKRUN)
            val restarted = FreeAiRequestLimiter { wall }.also { it.initialize(file) }
            assertEquals(wall + 30_000, restarted.cooldownUntilMillis(FreeAiProvider.POLLINATIONS))
            elapsed += 30_000
            assertNull(limiter.cooldownUntilMillis(FreeAiProvider.POLLINATIONS))
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun explicitStartupLoadCannotAdmitOrMigrateQuotaEarly() {
        val file = Files.createTempDirectory("startup-quota").resolve("state.json").toFile()
        try {
            val writer = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file) }
            repeat(2) { writer.reserve(FreeAiProvider.OVHCLOUD, "model") }
            val limiter = FreeAiRequestLimiter { 1_000_000L }.also { it.initialize(file, requireExplicitLoad = true) }
            assertThrows(IllegalStateException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD, "model") }
            limiter.loadState()
            assertEquals(1_060_000L, limiter.exhaustedAllowanceUntilMillis(FreeAiProvider.OVHCLOUD, "model"))
            assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD, "model") }
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun sleepCountsTowardCooldownAndRebootReconstructsANewElapsedOrigin() {
        var wall = 1_000_000L
        var elapsed = 900_000L
        val file = Files.createTempDirectory("sleep-quota").resolve("state.json").toFile()
        try {
            val provider = FreeAiProvider.OVHCLOUD
            val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall }).also { it.initialize(file) }
            repeat(2) { limiter.reserve(provider, "model") }
            // elapsedRealtime advances during deep sleep, unlike the previous nanoTime clock.
            wall += 45_000
            elapsed += 45_000
            assertEquals(wall + 15_000, limiter.exhaustedAllowanceUntilMillis(provider, "model"))
            // Reboot resets the monotonic origin. Only the remaining wall deadline is restored.
            var rebootElapsed = 100L
            val restarted = FreeAiRequestLimiter(elapsedMillis = { rebootElapsed }, nowMillis = { wall }).also { it.initialize(file) }
            assertEquals(wall + 15_000, restarted.exhaustedAllowanceUntilMillis(provider, "model"))
            wall += 15_000
            rebootElapsed += 15_000
            assertNull(restarted.exhaustedAllowanceUntilMillis(provider, "model"))
        } finally {
            file.parentFile.deleteRecursively()
        }
    }
}
