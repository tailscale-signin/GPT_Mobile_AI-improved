package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderThrottleRegistryTest {
    @Test fun cooledRequestsArePacedWhileOtherCredentialsRemainImmediate() {
        var now = 0L
        val registry = ProviderThrottleRegistry({ now }, { 0 })
        registry.defer("account-a", 5000)
        assertEquals(5000L, registry.remainingMillis("account-a"))
        assertEquals(0L, registry.remainingMillis("account-b"))
        now = 5000
        assertEquals(0L, registry.remainingMillis("account-a"))
        repeat(20) { assertEquals(1000L, registry.remainingMillis("account-a")) }
        now = 6000
        assertEquals(0L, registry.remainingMillis("account-a"))
        assertEquals(1000L, registry.remainingMillis("account-a"))
    }

    @Test fun laterRateLimitExtendsTheSharedDeadlineAndExpiredRecoveryDoesNotThrottleNormalChat() {
        var now = 0L
        val registry = ProviderThrottleRegistry({ now }, { 250 })
        registry.defer("account", 5000)
        now = 1000
        registry.defer("account", 10_000)
        assertEquals(10_000L, registry.remainingMillis("account"))
        now = 11_000
        assertEquals(0L, registry.remainingMillis("account"))
        assertTrue(registry.remainingMillis("account") in 1000L..1250L)
        now = 41_000
        repeat(20) { assertEquals(0L, registry.remainingMillis("account")) }
    }
}
