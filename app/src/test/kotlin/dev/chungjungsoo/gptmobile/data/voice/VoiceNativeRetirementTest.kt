package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNativeRetirementTest {
    @Test fun blockedWorkerRemainsQuarantinedAfterForegroundDeadline() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            worker.submit {
                started.countDown()
                release.await()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.shutdown()
            val retirement = VoiceNativeRetirement({ worker.awaitTermination(it, TimeUnit.MILLISECONDS) }, scope)
            assertEquals(VoiceNativeRetirementState.PENDING, retirement.awaitWithinBudget(25))
            assertFalse(worker.isTerminated)
            release.countDown()
            withTimeout(2_000) { retirement.awaitRetired() }
            assertTrue(worker.isTerminated)
            assertEquals(VoiceNativeRetirementState.RETIRED, retirement.state.value)
        } finally {
            release.countDown()
            worker.shutdownNow()
            scope.cancel()
        }
    }

    @Test fun cancellingAnObserverDoesNotCancelNativeRetirement() = runBlocking {
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            worker.submit { release.await() }
            worker.shutdown()
            val retirement = VoiceNativeRetirement({ worker.awaitTermination(it, TimeUnit.MILLISECONDS) }, scope)
            val observer = async { retirement.awaitRetired() }
            observer.cancelAndJoin()
            assertEquals(VoiceNativeRetirementState.PENDING, retirement.state.value)
            release.countDown()
            withTimeout(2_000) { retirement.awaitRetired() }
            assertEquals(VoiceNativeRetirementState.RETIRED, retirement.awaitWithinBudget(0))
        } finally {
            release.countDown()
            worker.shutdownNow()
            scope.cancel()
        }
    }

    @Test fun failedObservationCannotManufactureRetirement() = runBlocking {
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = AtomicBoolean(true)
        val observed = CountDownLatch(1)
        try {
            worker.submit { release.await() }
            worker.shutdown()
            val retirement = VoiceNativeRetirement({ timeout ->
                if (first.getAndSet(false)) throw IllegalStateException("observation unavailable")
                observed.countDown()
                worker.awaitTermination(timeout, TimeUnit.MILLISECONDS)
            }, scope)
            assertTrue(observed.await(2, TimeUnit.SECONDS))
            assertEquals(VoiceNativeRetirementState.PENDING, retirement.awaitWithinBudget(0))
            assertEquals(1, retirement.observationFailures)
            assertFalse(worker.isTerminated)
            release.countDown()
            withTimeout(2_000) { retirement.awaitRetired() }
            assertTrue(worker.isTerminated)
        } finally {
            release.countDown()
            worker.shutdownNow()
            scope.cancel()
        }
    }

    @Test fun alreadyRetiredWorkerCompletesAndKeepsElapsedTimeStable() = runBlocking {
        val worker = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            worker.shutdown()
            val retirement = VoiceNativeRetirement({ worker.awaitTermination(it, TimeUnit.MILLISECONDS) }, scope)
            assertEquals(VoiceNativeRetirementState.RETIRED, retirement.awaitWithinBudget(2_000))
            val elapsed = retirement.elapsedMs
            assertEquals(VoiceNativeRetirementState.RETIRED, retirement.awaitWithinBudget(0))
            assertEquals(elapsed, retirement.elapsedMs)
            assertEquals(0, retirement.observationFailures)
        } finally {
            worker.shutdownNow()
            scope.cancel()
        }
    }
}
