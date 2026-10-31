package dev.chungjungsoo.gptmobile.data.network

import java.io.EOFException
import javax.net.ssl.SSLException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointConnectionPolicyTest {
    @Test fun metadataAndGenerationShareOneRouteRecoveryAfterEof() = runTest {
        var attempts = 0
        val value = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29761/props") {
            attempts++
            if (attempts == 1) throw EOFException("unexpected end of stream")
            assertTrue(LocalEndpointConnectionPolicy.freshConnection("http://127.0.0.1:29761/v1/chat/completions"))
            "observed"
        }
        assertEquals("observed", value)
        assertEquals(2, attempts)
        assertFalse(LocalEndpointConnectionPolicy.freshConnection("http://127.0.0.1:29762/apply-template"))
    }

    @Test fun probeRetriesAreBoundedAndTlsFailuresAreNotReplayed() = runTest {
        for (failure in listOf(EOFException("unexpected end of stream"), SSLException("Certificate failed"))) {
            var attempts = 0
            val observed = runCatching {
                LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29763/props") {
                    attempts++
                    throw failure
                }
            }.exceptionOrNull()
            assertEquals(failure, observed)
            assertEquals(if (failure is EOFException) 2 else 1, attempts)
        }
    }
}
