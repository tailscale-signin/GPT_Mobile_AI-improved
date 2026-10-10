package dev.chungjungsoo.gptmobile.data.marketplace

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketplaceAvailabilityTest {
    @Test fun unavailableAndSetupStatesOfferTheRelevantAction() {
        val absent = resolveMcpMarketplaceAvailability(false, enabled = false, setupComplete = false)
        val setup = resolveMcpMarketplaceAvailability(true, enabled = false, setupComplete = false)

        assertEquals(MarketplaceAvailabilityCode.AVAILABLE, absent.code)
        assertEquals(MarketplaceAvailabilityAction.SETUP, absent.action)
        assertEquals(MarketplaceAvailabilityCode.NEEDS_SETUP, setup.code)
        assertEquals(MarketplaceAvailabilityAction.SETUP, setup.action)
    }

    @Test fun cooldownReachabilityAndReadyAreDistinct() {
        val now = 1_000L
        val cooldown = resolveMcpMarketplaceAvailability(true, true, true, retryAt = 5_000, now = now)
        val down = resolveMcpMarketplaceAvailability(true, true, true, reachable = false, now = now)
        val ready = resolveMcpMarketplaceAvailability(true, true, true, reachable = true, now = now)

        assertEquals(MarketplaceAvailabilityCode.COOLDOWN, cooldown.code)
        assertEquals(4_000L, cooldown.retryAt!! - now)
        assertEquals(MarketplaceAvailabilityCode.NOT_REACHABLE, down.code)
        assertEquals(MarketplaceAvailabilityCode.READY, ready.code)
    }
}
