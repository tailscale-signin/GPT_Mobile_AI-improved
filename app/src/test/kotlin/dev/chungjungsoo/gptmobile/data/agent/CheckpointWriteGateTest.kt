package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CheckpointWriteGateTest {
    @Test fun contributorsCoalesceAfterContentionAndTerminalDrainPassesReplaceableProgress() = runTest {
        val gate = CheckpointWriteGate()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val first = ConflatedCheckpointWriter<String>(this, writeGate = gate) {
            release.await()
            writes += it
        }
        val progress = ConflatedCheckpointWriter<String>(this, writeGate = gate) { writes += it }
        val terminal = ConflatedCheckpointWriter<String>(this, writeGate = gate) { writes += it }
        first.offer("active")
        runCurrent()
        progress.offer("old")
        terminal.offer("terminal")
        runCurrent()
        progress.offer("newest")
        val drain = launch { terminal.drain() }
        runCurrent()
        release.complete(Unit)
        drain.join()
        first.drain()
        progress.drain()
        assertEquals(listOf("active", "terminal", "newest"), writes)
    }

    @Test fun cancelledWaitingContributorCannotWriteOrLeakTheSharedPermit() = runTest {
        val gate = CheckpointWriteGate()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val first = launch {
            gate.withPermit({ false }) {
                release.await()
                writes += 1
            }
        }
        runCurrent()
        val cancelled = launch { gate.withPermit({ false }) { writes += 2 } }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        val next = launch { gate.withPermit({ true }) { writes += 3 } }
        runCurrent()
        release.complete(Unit)
        first.join()
        next.join()
        assertEquals(listOf(1, 3), writes)
    }
}
