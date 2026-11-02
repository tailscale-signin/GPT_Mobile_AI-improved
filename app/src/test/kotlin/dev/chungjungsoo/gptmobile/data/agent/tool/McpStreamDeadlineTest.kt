package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStreamDeadlineTest {
    @Test fun heartbeatLinesKeepTheStreamLiveAndSilenceHasAnExplicitFailure() = runTest {
        repeat(3) {
            assertEquals(
                ": heartbeat",
                withMcpStreamIdleDeadline(100) {
                    delay(90)
                    ": heartbeat"
                }
            )
        }
        val failure = runCatching { withMcpStreamIdleDeadline(100) { delay(101) } }.exceptionOrNull()
        assertTrue(failure is McpStreamIdleTimeoutException)
    }

    @Test fun callerCancellationRemainsCancellation() = runTest {
        val read = async { withMcpStreamIdleDeadline(1000) { awaitCancellation() } }
        kotlinx.coroutines.yield()
        read.cancel(CancellationException("screen disposed"))
        assertTrue(runCatching { read.await() }.exceptionOrNull() is CancellationException)
    }
}
