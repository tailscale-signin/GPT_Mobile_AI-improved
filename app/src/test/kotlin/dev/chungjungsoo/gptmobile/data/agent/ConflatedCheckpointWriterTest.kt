package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedCheckpointWriterTest {
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
}
