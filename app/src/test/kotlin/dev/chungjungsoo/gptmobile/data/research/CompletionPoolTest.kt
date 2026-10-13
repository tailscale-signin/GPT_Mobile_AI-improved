package dev.chungjungsoo.gptmobile.data.research

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionPoolTest {
    @Test fun freeSlotRefillsBeforeSlowFirstItemFinishes() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val fast = CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        val job = async {
            completionPool(listOf(0, 1, 2), 2) {
                if (it == 0) release.await()
                seen += it
                if (it == 2) fast.complete(Unit)
            }
        }
        try {
            withTimeout(2000) { fast.await() }
            assertEquals(listOf(1, 2), seen)
        } finally {
            release.complete(Unit)
        }
        job.await()
        assertEquals(listOf(1, 2, 0), seen)
    }

    @Test fun cancellationStopsWorkersAndDoesNotDispatchTail() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var dispatched = 0
        val job = async {
            completionPool((1..100).toList(), 1) {
                dispatched++
                entered.complete(Unit)
                never.await()
            }
        }
        withTimeout(2000) { entered.await() }
        job.cancel()
        job.join()
        assertEquals(1, dispatched)
    }
}
