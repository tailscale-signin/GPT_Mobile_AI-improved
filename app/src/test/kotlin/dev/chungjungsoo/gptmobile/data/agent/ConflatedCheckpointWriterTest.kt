package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedCheckpointWriterTest {
    @Test fun terminalDrainBypassesIntermediateCoalescingDelay() = runTest {
        val committed = mutableListOf<Int>()
        val writer = ConflatedCheckpointWriter<Int>(this, minimumIntervalMillis = 10_000) { committed += it }
        writer.offer(1)
        runCurrent()
        writer.offer(2)
        runCurrent()
        val start = testScheduler.currentTime
        writer.drain()
        assertEquals(start, testScheduler.currentTime)
        assertEquals(listOf(1, 2), committed)
    }

    @Test fun fastDatabaseStillCoalescesTokenUpdatesAndFlushesLatestAtDrain() = runTest {
        val committed = mutableListOf<Int>()
        val writer = ConflatedCheckpointWriter<Int>(this, minimumIntervalMillis = 500) { committed += it }
        writer.offer(0)
        runCurrent()
        repeat(100) {
            writer.offer(it + 1)
            runCurrent()
        }
        assertEquals(listOf(0), committed)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(0, 100), committed)
        writer.offer(101)
        writer.drain()
        assertEquals(listOf(0, 100, 101), committed)
    }

    @Test fun slowWritesKeepOnlyNewestPendingSnapshotAndDrainBeforeTerminal() = runTest {
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val writer = ConflatedCheckpointWriter<String>(this) {
            if (it == "first") release.await()
            committed += it
        }
        writer.offer("first")
        runCurrent()
        repeat(1000) { writer.offer("pending $it") }
        val terminal = launch {
            writer.drain()
            committed += "terminal"
        }
        runCurrent()
        assertTrue(committed.isEmpty())
        release.complete(Unit)
        terminal.join()
        assertEquals(listOf("first", "pending 999", "terminal"), committed)
    }

    @Test fun supersededWriterCannotWriteAfterTerminal() = runTest {
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val writer = ConflatedCheckpointWriter<String>(this) {
            release.await()
            committed += it
        }
        writer.offer("old")
        runCurrent()
        writer.offer("pending")
        writer.cancelAndJoin()
        committed += "terminal"
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("terminal"), committed)
    }

    @Test fun writeFailureIsReportedAtDrain() = runTest {
        val writer = ConflatedCheckpointWriter<String>(this) { error("disk unavailable") }
        writer.offer("first")
        runCurrent()
        assertEquals("disk unavailable", runCatching { writer.drain() }.exceptionOrNull()?.message)
    }

    @Test fun unchangedSnapshotsDoNotRewriteButFinalChangeIsDurable() = runTest {
        val committed = mutableListOf<String>()
        val writer = ConflatedCheckpointWriter<String>(this) { committed += it }
        writer.offer("same")
        runCurrent()
        writer.offer("same")
        runCurrent()
        writer.offer("final")
        writer.drain()
        assertEquals(listOf("same", "final"), committed)
        assertEquals(1L, writer.skippedUnchangedWrites)
        assertEquals(2L, writer.completedWrites)
    }
}
