package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FairNativeLockTest {
    @Test fun visibleRequestsCannotStarveEnrichmentAndPriorityDoesNotPreemptOwner() = runTest {
        val lock = FairNativeLock { testScheduler.currentTime }
        assertTrue(lock.tryLock())
        val order = mutableListOf<String>()
        val helper = launch(NativeWorkPriority(NativeWorkClass.ENRICHMENT, "chat-a")) {
            lock.lock()
            try {
                order += "helper"
            } finally {
                lock.unlock()
            }
        }
        runCurrent()
        val visible = (1..5).map { index ->
            launch {
                lock.lock()
                try {
                    order += "visible-$index"
                } finally {
                    lock.unlock()
                }
            }
        }
        runCurrent()
        assertTrue(order.isEmpty())
        lock.unlock()
        helper.join()
        visible.forEach { it.join() }
        assertEquals(listOf("visible-1", "visible-2", "visible-3", "helper", "visible-4", "visible-5"), order)
        assertFalse(lock.isLocked)
    }

    @Test fun cancelledHandoffReleasesOwnershipForRemainingWaiter() = runTest {
        val lock = FairNativeLock { 0L }
        lock.lock()
        val cancelled = launch {
            lock.lock()
            try {
                error("Cancelled waiter executed")
            } finally {
                lock.unlock()
            }
        }
        var executed = false
        val survivor = launch {
            lock.lock()
            try {
                executed = true
            } finally {
                lock.unlock()
            }
        }
        runCurrent()
        lock.unlock() // Grant before the resumed waiter gets a scheduler turn.
        cancelled.cancelAndJoin()
        survivor.join()
        assertTrue(executed)
        assertFalse(lock.isLocked)
    }

    @Test fun samePriorityRootsAlternateBeforeAging() = runTest {
        val lock = FairNativeLock { 0L }
        lock.lock()
        val order = mutableListOf<String>()
        val jobs = listOf("a", "a", "b").map { root ->
            launch(NativeWorkPriority(NativeWorkClass.HELPER, root)) {
                lock.lock()
                try {
                    order += root
                } finally {
                    lock.unlock()
                }
            }
        }
        runCurrent()
        lock.unlock()
        jobs.forEach { it.join() }
        assertEquals(listOf("a", "b", "a"), order)
    }

    @Test fun oldEnrichmentEventuallyLeads() = runTest {
        var now = 0L
        val lock = FairNativeLock { now }
        lock.lock()
        val order = mutableListOf<String>()
        val jobs = listOf("a", "a", "b").map { root ->
            launch(NativeWorkPriority(NativeWorkClass.HELPER, root)) {
                lock.lock()
                try {
                    order += root
                } finally {
                    lock.unlock()
                }
            }
        }
        runCurrent()
        now = 60_001
        val visible = launch {
            lock.lock()
            try {
                order += "visible"
            } finally {
                lock.unlock()
            }
        }
        runCurrent()
        lock.unlock()
        jobs.forEach { it.join() }
        visible.join()
        assertEquals(listOf("a", "a", "b", "visible"), order) // Aging outranks priority and root balancing.
        assertFalse(lock.isLocked)
    }
}
