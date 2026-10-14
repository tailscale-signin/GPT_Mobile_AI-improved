package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTerminalOrderingTest {
    @Test fun completedCallbackIsAcknowledgedBeforeItsTerminalEventIsVisible() = runTest {
        val completion = CompletableDeferred<Unit>()
        acknowledgeNativeTerminal(completion) {
            // Simulate an immediately resumed collector, before the native callback returns.
            assertTrue("A completed callback must never enter cancellation cleanup", completion.isCompleted)
        }
        completion.await()
    }

    @Test fun failedTerminalDeliveryCannotRevokeAnAcknowledgedCallback() {
        val completion = CompletableDeferred<Unit>()
        val failure = runCatching {
            acknowledgeNativeTerminal(completion) { error("collector already closed") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(completion.isCompleted)
    }
}
