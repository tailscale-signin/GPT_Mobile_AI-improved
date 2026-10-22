package dev.chungjungsoo.gptmobile.data.research

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchStorageGateTest {
    @Test fun blockedTaskDoesNotBlockAnotherTaskButDeletionWaitsForCommit() = runBlocking {
        val gate = ResearchStorageGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = async {
            gate.withReadLock {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        withTimeout(2000) { gate.withReadLock { assertFalse(slow.isCompleted) } }
        val deleted = CompletableDeferred<Unit>()
        val cleanup = async { gate.withLock { deleted.complete(Unit) } }
        kotlinx.coroutines.yield()
        assertFalse(deleted.isCompleted)
        release.complete(Unit)
        withTimeout(2000) {
            slow.await()
            cleanup.await()
        }
        gate.withReadLock { assertTrue(deleted.isCompleted) }
    }

    @Test fun cancelledMaintenanceDoesNotLeaveReadersPermanentlyQueued() = runBlocking {
        val gate = ResearchStorageGate()
        gate.withReadLock {
            val writer = async { gate.withLock { error("Should not enter") } }
            kotlinx.coroutines.yield()
            writer.cancel()
            writer.join()
        }
        withTimeout(2000) { gate.withReadLock { } }
    }
}
