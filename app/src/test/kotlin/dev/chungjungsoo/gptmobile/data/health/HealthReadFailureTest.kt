package dev.chungjungsoo.gptmobile.data.health

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReadFailureTest {
    @Test fun permissionAndArgumentsHaveDifferentRemediation() {
        val permission = HealthReadFailure.classify(SecurityException("private health text"))
        assertEquals("permission_required", permission.code)
        assertFalse(permission.retryable)
        assertFalse(permission.toString().contains("private health text"))
        assertEquals("invalid_health_request", HealthReadFailure.classify(IllegalArgumentException()).code)
    }

    @Test fun transientCausesRemainRetryableButDoNotIncludePrivateMessages() {
        val failure = HealthReadFailure.classify(IOException("private", SocketTimeoutException("private data")))
        assertEquals("health_connect_timeout", failure.code)
        assertTrue(failure.retryable)
        assertFalse(failure.toString().contains("private"))
    }
}
