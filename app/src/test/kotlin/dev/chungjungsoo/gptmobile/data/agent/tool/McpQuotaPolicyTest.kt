package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpQuotaPolicyTest {
    @Test
    fun quotaErrorsOpenCircuitWhileTransientAndPermissionErrorsDoNot() {
        assertTrue(isMcpQuotaFailure("Today's 50 free anonymous calls are used up."))
        assertTrue(isMcpQuotaFailure("HTTP 429"))
        assertFalse(isMcpQuotaFailure("Connection closed"))
        assertFalse(isMcpQuotaFailure("HTTP 401: authenticate"))
    }
}
