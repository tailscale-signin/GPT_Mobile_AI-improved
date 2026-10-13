package dev.chungjungsoo.gptmobile.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationExitWorkTest {
    @Test fun `exit schedules without waiting and reopening observes the final draft`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val worker = ConversationExitWork(this)
        var saved = "old"
        val save = worker.enqueue(7) {
            release.await()
            saved = "latest"
        }
        val reopen = async(start = CoroutineStart.UNDISPATCHED) {
            worker.awaitPending(7)
            saved
        }
        try {
            assertFalse(save.isCompleted)
            assertFalse(reopen.isCompleted)
        } finally {
            release.complete(Unit)
        }
        assertEquals("latest", reopen.await())
    }

    @Test fun `exit work for one chat is ordered while other chats remain independent`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val worker = ConversationExitWork(this)
        val writes = mutableListOf<Int>()
        worker.enqueue(7) {
            release.await()
            writes += 1
        }
        val last = worker.enqueue(7) { writes += 2 }
        worker.enqueue(8) { writes += 8 }.join()
        try {
            assertEquals(listOf(8), writes)
        } finally {
            release.complete(Unit)
        }
        last.join()
        assertEquals(listOf(8, 1, 2), writes)
    }
}
