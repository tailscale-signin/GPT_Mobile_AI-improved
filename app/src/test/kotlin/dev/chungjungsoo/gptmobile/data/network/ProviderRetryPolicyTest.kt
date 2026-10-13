package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRetryPolicyTest {
    @Test fun `retry after respects seconds and dates without accepting malformed values`() {
        assertEquals(30_000L, ProviderRetryPolicy.retryAfterMillis("30", 0))
        assertEquals(10_000L, ProviderRetryPolicy.retryAfterMillis("Thu, 1 Jan 1970 00:00:10 GMT", 0))
        assertEquals(0L, ProviderRetryPolicy.retryAfterMillis("Thu, 1 Jan 1970 00:00:10 GMT", 20_000))
        assertNull(ProviderRetryPolicy.retryAfterMillis("-5", 0))
        assertNull(ProviderRetryPolicy.retryAfterMillis("invalid", 0))
    }
}
