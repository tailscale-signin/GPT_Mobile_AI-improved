package dev.chungjungsoo.gptmobile.data.research.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExaminationWakeupCoordinatorTest {
    private class Queue {
        val coordinator = ExaminationWakeupCoordinator()
        val outbox = linkedSetOf<String>()
        val processed = mutableListOf<String>()
        var running = false
        var waiting = 0
        var admissions = 0

        suspend fun signal() = coordinator.enqueue({ waiting > 0 }) {
            admissions++
            waiting++
        }

        fun startAndRead() {
            check(!running && waiting > 0)
            waiting--
            running = true
            processed += outbox
            outbox.clear()
        }

        fun complete() {
            check(running)
            running = false
        }
    }

    @Test fun aCommittedItemAfterTheLastQueueReadHasADurableSuccessor() = runTest {
        val queue = Queue()
        queue.outbox += "first"
        assertTrue(queue.signal())
        queue.startAndRead()

        // The active attempt has finished scanning, but WorkManager has not
        // committed success yet. KEEP would discard this wake-up.
        queue.outbox += "arrived_during_completion"
        assertTrue(queue.signal())
        assertTrue(queue.running)
        assertEquals(1, queue.waiting)
        queue.complete()
        queue.startAndRead()
        queue.complete()

        assertEquals(listOf("first", "arrived_during_completion"), queue.processed)
        assertTrue(queue.outbox.isEmpty())
    }

    @Test fun concurrentSignalsCoalesceBehindTheActiveWorkerWithoutCancellingIt() = runTest {
        val queue = Queue()
        queue.outbox += "initial"
        queue.signal()
        queue.startAndRead()
        val scheduled = (1..40).map { number ->
            async {
                queue.outbox += "item_$number"
                queue.signal()
            }
        }.awaitAll()

        assertEquals(1, scheduled.count { it })
        assertEquals(2, queue.admissions)
        assertEquals(1, queue.waiting)
        assertTrue(queue.running)
        queue.complete()
        queue.startAndRead()
        queue.complete()
        assertEquals(41, queue.processed.toSet().size)
        assertTrue(queue.outbox.isEmpty())
    }

    @Test fun completionBetweenStateObservationAndAppendStillSchedulesFreshWork() = runTest {
        val queue = Queue()
        queue.signal()
        queue.startAndRead()
        queue.outbox += "late"
        queue.coordinator.enqueue(
            hasWaitingWork = {
                val waiting = queue.waiting > 0
                queue.complete()
                waiting
            },
            append = { queue.waiting++ }
        )
        queue.startAndRead()
        queue.complete()
        assertEquals(listOf("late"), queue.processed)
    }

    @Test fun callersWaitForTheEnqueueReceiptBeforeCheckingForAnotherSuccessor() = runTest {
        val coordinator = ExaminationWakeupCoordinator()
        val receipt = CompletableDeferred<Unit>()
        var waiting = false
        var observations = 0
        var admissions = 0
        suspend fun signal() = coordinator.enqueue(
            hasWaitingWork = {
                observations++
                waiting
            },
            append = {
                admissions++
                receipt.await()
                waiting = true
            }
        )
        val first = async { signal() }
        val second = async { signal() }
        runCurrent()
        assertEquals(1, observations)
        assertEquals(1, admissions)
        receipt.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(1, admissions)
    }

    @Test fun failedAdmissionLeavesTheDurableItemAndReleasesSchedulingForTheNextWake() = runTest {
        val queue = Queue()
        queue.outbox += "retained"
        val failure = runCatching {
            queue.coordinator.enqueue({ false }) { error("WorkManager unavailable") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(setOf("retained"), queue.outbox)
        assertTrue(queue.signal())
        queue.startAndRead()
        queue.complete()
        assertEquals(listOf("retained"), queue.processed)
    }
}
