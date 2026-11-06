package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayObservationPolicyTest {
    @Test fun exhaustedPhonePollingRetainsServerCheckpoint() {
        for (attempt in 0..2) assertEquals(GatewayObservationPolicy.Next.RETRY, GatewayObservationPolicy.next(false, attempt))
        for (attempt in 3..10) assertEquals(GatewayObservationPolicy.Next.KEEP_CHECKPOINT, GatewayObservationPolicy.next(false, attempt))
        assertEquals(GatewayObservationPolicy.Next.OBSERVED, GatewayObservationPolicy.next(true, 10))
    }
}
