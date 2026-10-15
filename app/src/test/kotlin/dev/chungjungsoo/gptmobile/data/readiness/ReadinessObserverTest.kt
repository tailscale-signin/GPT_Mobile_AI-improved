package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadinessObserverTest {
    @Test fun failedObserverRestartsAfterBackoffAndCancellationStopsIt() = runTest {
        var attempts = 0
        var failures = 0
        val job = backgroundScope.launchReadinessObserver("test", onFailure = { failures++ }) {
            attempts++
            if (attempts == 1) throw NoSuchElementException()
            awaitCancellation()
        }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, failures)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, attempts)
        job.cancelAndJoin()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, failures)
    }
}
