package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.EOFException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpReadRecoveryTest {
    @Test fun incompleteCatalogReadRetriesOnceAndRetainsAlreadyCompletedPages() = runTest {
        val previousPages = mutableListOf("first page")
        var calls = 0
        val nextPage = withMcpReadRecovery("tools/list", pause = { }) {
            calls++
            if (calls == 1) throw McpIncompleteResponseException("tools/list", 50)
            "second page"
        }
        previousPages += nextPage
        assertEquals(listOf("first page", "second page"), previousPages)
        assertEquals(2, calls)
    }

    @Test fun dnsRecoveryIsBoundedAndDoesNotReplayMutatingCalls() = runTest {
        for (method in listOf("tools/list", "tools/call", "tasks/update", "tasks/cancel")) {
            var calls = 0
            val failure = runCatching {
                withMcpReadRecovery(method, pause = { }) {
                    calls++
                    throw UnknownHostException("transient")
                }
            }.exceptionOrNull()
            assertTrue(failure is UnknownHostException)
            assertEquals(if (method == "tools/list") 2 else 1, calls)
        }
    }

    @Test fun authorizationProtocolAndCancellationAreNotRetried() = runTest {
        for (expected in listOf(ModernMcpError(401, null, "Denied"), ModernMcpError(200, -32602, "Invalid arguments"), CancellationException("backgrounded"))) {
            var calls = 0
            val failure = runCatching {
                withMcpReadRecovery("tools/list", pause = { }) {
                    calls++
                    throw expected
                }
            }.exceptionOrNull()
            assertTrue(expected === failure)
            assertEquals(1, calls)
        }
        var writes = 0
        runCatching {
            withMcpReadRecovery("tools/call", pause = { }) {
                writes++
                throw EOFException()
            }
        }
        assertEquals(1, writes)
    }

    @Test fun plainEofReadRecoveryDoesNotTurnAnSslWrappedEofIntoAnAutomaticRetry() = runTest {
        val tls = javax.net.ssl.SSLException("Secure stream ended", EOFException("unexpected end of stream"))
        for ((expected, expectedCalls) in listOf(EOFException("unexpected end of stream") to 2, tls to 1, java.io.IOException("SDK wrapper", tls) to 1)) {
            var calls = 0
            val observed = runCatching {
                withMcpReadRecovery("tools/list", pause = { }) {
                    calls++
                    throw expected
                }
            }.exceptionOrNull()
            assertTrue(expected === observed)
            assertEquals(expectedCalls, calls)
        }
    }

    @Test fun requestDeadlineIsTransportFailureWhileParentDeadlineRemainsCancellation() = runTest {
        val deadline = runCatching { withMcpRequestDeadline(10, "tools/call") { delay(20) } }.exceptionOrNull()
        assertTrue(deadline is McpRequestTimeoutException)
        assertTrue(deadline?.message.orEmpty().contains("UNKNOWN_OUTCOME"))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val parent = runCatching { withTimeout(5) { withMcpRequestDeadline(10, "tools/call") { delay(20) } } }.exceptionOrNull()
        assertTrue(parent is CancellationException)
    }
}
