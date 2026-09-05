package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class NetworkRetryUtilsTest {

    @Test
    fun isTransientNetworkError_returnsTrueForTransientErrors() {
        assertTrue(NetworkRetryUtils.isTransientNetworkError(SocketTimeoutException("timeout")))
        assertTrue(NetworkRetryUtils.isTransientNetworkError(ConnectException("connection refused")))
        assertTrue(NetworkRetryUtils.isTransientNetworkError(UnknownHostException("no such host")))
        assertTrue(NetworkRetryUtils.isTransientNetworkError(IOException("Connection reset by peer")))
    }

    @Test
    fun isTransientNetworkError_returnsFalseForCancellationAndGeneralErrors() {
        assertFalse(NetworkRetryUtils.isTransientNetworkError(CancellationException("cancelled")))
        assertFalse(NetworkRetryUtils.isTransientNetworkError(IllegalArgumentException("bad arg")))
        assertFalse(NetworkRetryUtils.isTransientNetworkError(IllegalStateException("invalid state")))
    }

    @Test
    fun withRetry_succeedsImmediatelyWhenNoException() = runTest {
        var attempts = 0
        val result = NetworkRetryUtils.withRetry(maxRetries = 2) {
            attempts++
            "success"
        }
        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun withRetry_retriesOnTransientErrorAndSucceeds() = runTest {
        var attempts = 0
        val result = NetworkRetryUtils.withRetry(
            maxRetries = 2,
            initialDelayMs = 10L,
            maxDelayMs = 50L
        ) {
            attempts++
            if (attempts == 1) {
                throw SocketTimeoutException("temporary timeout")
            }
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, attempts)
    }

    @Test(expected = SocketTimeoutException::class)
    fun withRetry_throwsAfterExhaustingRetries() = runTest {
        var attempts = 0
        NetworkRetryUtils.withRetry(
            maxRetries = 2,
            initialDelayMs = 5L,
            maxDelayMs = 20L
        ) {
            attempts++
            throw SocketTimeoutException("continuous timeout")
        }
    }
}
