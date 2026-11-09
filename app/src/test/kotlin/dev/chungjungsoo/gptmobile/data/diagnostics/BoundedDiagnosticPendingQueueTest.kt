package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDiagnosticPendingQueueTest {
    @Test fun exportSnapshotPreservesTheLiveConsumersOrderAndDoesNotStealOrDuplicateQueuedRecords() {
        val queue = BoundedDiagnosticPendingQueue<String>()
        queue.offer("ordinary-first", 100, false)
        queue.offer("critical", 100, true)
        queue.offer("ordinary-second", 100, false)
        val captured = queue.snapshot()
        assertEquals(listOf("critical", "ordinary-first", "ordinary-second"), captured)
        assertEquals(3, queue.size)
        assertEquals("critical", queue.poll())
        queue.offer("later", 100, false)
        assertEquals(listOf("ordinary-first", "ordinary-second", "later"), queue.drainBatch())
        assertEquals(listOf("critical", "ordinary-first", "ordinary-second"), captured)
        assertEquals(0, queue.size)
    }

    @Test fun ordinaryBurstCannotEvictCriticalEvidenceAndOverflowCountsEveryLoss() {
        var ordinaryDrops = 0
        var criticalDrops = 0
        val queue = BoundedDiagnosticPendingQueue<String>(100, 40, 8, 2) { if (it) criticalDrops++ else ordinaryDrops++ }
        assertTrue(queue.offer("crash", 25, isCritical = true))
        repeat(30) { queue.offer("ordinary-$it", 10, isCritical = false) }
        assertEquals(85, queue.retainedCharacters)
        assertEquals(24, ordinaryDrops)
        assertEquals(0, criticalDrops)
        val batch = queue.drainBatch(8, 100)
        assertEquals("crash", batch.first())
        assertEquals("ordinary-29", batch.last())
        assertEquals(0, queue.retainedCharacters)
    }

    @Test fun criticalOverflowKeepsItsNewestEvidenceWithinReservedCapacity() {
        var criticalDrops = 0
        val queue = BoundedDiagnosticPendingQueue<String>(100, 40, 8, 2) { if (it) criticalDrops++ }
        queue.offer("ordinary", 10, isCritical = false)
        repeat(10) { queue.offer("critical-$it", 20, isCritical = true) }
        assertEquals(8, criticalDrops)
        assertEquals(listOf("critical-8", "critical-9", "ordinary"), queue.drainBatch())
        assertEquals(0, queue.size)
    }

    @Test fun anOversizedRecordIsRejectedWithoutEvictingRetainedEvidence() {
        var drops = 0
        val queue = BoundedDiagnosticPendingQueue<String>(100, 40, 8, 2) { drops++ }
        queue.offer("retained", 20, isCritical = false)
        assertFalse(queue.offer("oversized", 61, isCritical = false))
        assertFalse(queue.offer("oversized-critical", 41, isCritical = true))
        assertEquals(2, drops)
        assertEquals("retained", queue.poll())
    }

    @Test fun wakingAConsumerDoesNotRemoveEvidenceBeforeItCanTakeTheSnapshotLock() = runBlocking {
        val queue = BoundedDiagnosticPendingQueue<String>()
        val waiting = async(Dispatchers.Default) { withTimeout(5000) { queue.awaitRecords() } }
        queue.offer("critical", 100, isCritical = true)
        waiting.await()
        assertEquals(1, queue.size)
        assertEquals("critical", queue.poll())
        // A stale conflated wake-up after clearing must not masquerade as another record.
        queue.clear()
        val next = async(Dispatchers.Default) {
            withTimeout(5000) {
                queue.awaitRecords()
                queue.poll()
            }
        }
        queue.offer("next", 100, isCritical = false)
        assertEquals("next", next.await())
    }

    @Test fun concurrentProducersStayBoundedAndAccountForAllEvictions() {
        val drops = AtomicLong()
        val queue = BoundedDiagnosticPendingQueue<String>(4096, 1024, 64, 16) { drops.incrementAndGet() }
        val producers = List(4) { producer ->
            thread {
                repeat(5000) { index -> queue.offer("$producer-$index", 100, isCritical = index % 10 == 0) }
            }
        }
        producers.forEach { it.join() }
        assertTrue(queue.retainedCharacters <= 4096)
        assertTrue(queue.size <= 64)
        assertEquals(20_000L, drops.get() + queue.size)
        val captured = queue.drainBatch(64, 4096)
        assertEquals(20_000L - drops.get(), captured.size.toLong())
    }

    @Test fun drainedBatchesHaveAnIndependentPayloadBound() {
        val queue = BoundedDiagnosticPendingQueue<Int>()
        repeat(40) { queue.offer(it, 8000, isCritical = it % 5 == 0) }
        while (queue.size > 0) {
            val batch = queue.drainBatch()
            assertTrue(batch.size * 8000 <= 32 * 1024)
        }
        assertEquals(0, queue.retainedCharacters)
    }

    @Test fun deliberatePrivacyClearDoesNotReportOverflowLoss() {
        var drops = 0
        val queue = BoundedDiagnosticPendingQueue<String> { drops++ }
        queue.offer("critical", 100, isCritical = true)
        queue.offer("ordinary", 100, isCritical = false)
        queue.clear()
        assertEquals(0, queue.size)
        assertEquals(0, queue.retainedCharacters)
        assertEquals(0, drops)
    }
}
