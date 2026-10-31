package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineeringRetryDeadlineTest {
    @Test fun secondaryLimitWithoutRetryHeadersWaitsInsteadOfBlockingTheWatch() {
        val error = engineeringApiFailure(403, null, "200", "4999", null, "You have exceeded a secondary rate limit", 1000)
        assertEquals("GITHUB_SECONDARY_RATE_LIMITED", error.category)
        assertEquals(61_000L, error.retryAtEpochMs)
        val permission = engineeringApiFailure(403, null, "200", "4999", null, "Resource not accessible by integration", 1000)
        assertEquals("GITHUB_ENDPOINT_PERMISSION_REQUIRED", permission.category)
        assertNull(permission.retryAtEpochMs)
    }

    @Test fun authAndPermissionErrorsDoNotWaitForOrdinaryQuotaResetHeaders() {
        assertNull(engineeringRetryDeadline(401, null, "200", "50", 1000))
        assertNull(engineeringRetryDeadline(403, null, "200", "50", 1000))
        assertEquals(200_000L, engineeringRetryDeadline(403, null, "200", "0", 1000))
        assertEquals(200_000L, engineeringRetryDeadline(429, null, "200", null, 1000))
    }

    @Test fun serverDatesAndLongWaitsArePreserved() {
        assertEquals(10_000L, engineeringRetryDeadline(503, "Thu, 1 Jan 1970 00:00:10 GMT", null, null, 1000))
        assertEquals(172_801_000L, engineeringRetryDeadline(429, "172800", "200", "0", 1000))
        assertEquals(Long.MAX_VALUE, engineeringRetryDeadline(429, "999999999999999999999", null, null, 10_000))
        assertNull(engineeringRetryDeadline(429, "invalid", "-1", null, 1000))
    }
}
