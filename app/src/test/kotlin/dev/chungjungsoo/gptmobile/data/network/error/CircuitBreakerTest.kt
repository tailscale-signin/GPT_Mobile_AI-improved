package dev.chungjungsoo.gptmobile.data.network.error

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CircuitBreakerTest {

    @Test
    fun startsInClosedState() {
        val breaker = CircuitBreaker(
            name = "test-breaker",
            config = CircuitBreaker.Config(failureThreshold = 3, resetTimeoutMs = 1000L)
        )
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.failureCount)
    }

    @Test
    fun transitionsToOpenWhenFailureThresholdExceeded() {
        val breaker = CircuitBreaker(
            name = "test-breaker",
            config = CircuitBreaker.Config(failureThreshold = 2, resetTimeoutMs = 10000L)
        )

        try {
            breaker.execute { throw IOException("Failed request 1") }
        } catch (_: IOException) {}
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)

        try {
            breaker.execute { throw IOException("Failed request 2") }
        } catch (_: IOException) {}
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertEquals(2, breaker.failureCount)
    }

    @Test
    fun rejectsCallsWhenOpen() {
        val breaker = CircuitBreaker(
            name = "test-breaker",
            config = CircuitBreaker.Config(failureThreshold = 1, resetTimeoutMs = 100000L)
        )

        try {
            breaker.execute { throw RuntimeException("Fault") }
        } catch (_: RuntimeException) {}

        try {
            breaker.execute { "should not run" }
            fail("Expected CircuitBreakerOpenException")
        } catch (e: CircuitBreakerOpenException) {
            assertEquals("test-breaker", e.circuitBreakerName)
        }
    }

    @Test
    fun transitionsToHalfOpenAndClosesOnSuccesses() {
        val breaker = CircuitBreaker(
            name = "test-breaker",
            config = CircuitBreaker.Config(
                failureThreshold = 1,
                resetTimeoutMs = 10L,
                halfOpenSuccessThreshold = 2
            )
        )

        try {
            breaker.execute { throw RuntimeException("Error") }
        } catch (_: RuntimeException) {}

        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        Thread.sleep(25L)

        // First trial request
        val result1 = breaker.execute { "ok1" }
        assertEquals("ok1", result1)
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state)

        // Second trial request reaches threshold to close
        val result2 = breaker.execute { "ok2" }
        assertEquals("ok2", result2)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    @Test
    fun suspendExecutionWorksCorrectly() = runBlocking {
        val breaker = CircuitBreaker(name = "suspend-breaker")
        val res = breaker.executeSuspend { "suspend-ok" }
        assertEquals("suspend-ok", res)
    }
}
