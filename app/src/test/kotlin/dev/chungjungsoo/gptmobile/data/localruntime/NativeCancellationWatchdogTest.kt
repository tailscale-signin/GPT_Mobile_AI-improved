package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCancellationWatchdogTest {
    @Test fun activeNativeLeaseMustTerminateEvenAfterJobCompletion() {
        assertTrue(NativeCancellationWatchdog.shouldTerminate(false, "request-a", "request-a", true))
    }

    @Test fun incompleteCoroutineWithoutActiveLeaseMustTerminate() {
        assertTrue(NativeCancellationWatchdog.shouldTerminate(false, "request-a", null, false))
    }

    @Test fun completedRequestWithoutNativeLeaseIsSafe() {
        assertFalse(NativeCancellationWatchdog.shouldTerminate(false, "request-a", null, true))
    }

    @Test fun differentActiveRequestCannotTriggerTerminationAfterCompletion() {
        assertFalse(NativeCancellationWatchdog.shouldTerminate(false, "request-a", "request-b", true))
    }

    @Test fun shutdownSuppressesFurtherTermination() {
        assertFalse(NativeCancellationWatchdog.shouldTerminate(true, "request-a", "request-a", false))
    }

    @Test fun unknownJobWithoutNativeLeaseDoesNotTerminate() {
        assertFalse(NativeCancellationWatchdog.shouldTerminate(false, "request-a", null, null))
    }
}
