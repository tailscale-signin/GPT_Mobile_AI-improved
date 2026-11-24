package dev.chungjungsoo.gptmobile.data.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDatabasePreparationTest {
    @Test fun openCompletesOnIoBeforeCallerStartsDependentObservers() = runBlocking {
        val caller = Thread.currentThread()
        val order = mutableListOf<String>()
        prepareStartupConversationDatabase {
            assertNotEquals(caller, Thread.currentThread())
            order += "database-open"
        }
        order += "observer"
        assertEquals(listOf("database-open", "observer"), order)
    }

    @Test fun keyOrOpenFailuresBlockObserversAndRemainRetryableByStartupOwner() = runBlocking {
        val failure = IllegalStateException("key unavailable")
        var observerStarted = false
        val caught = runCatching {
            prepareStartupConversationDatabase { throw failure }
            observerStarted = true
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertTrue(!observerStarted)
        var opened = 0
        prepareStartupConversationDatabase { opened++ }
        assertEquals(1, opened)
    }

    @Test fun cancellingStartupDuringOpenCannotStartDependentObservers() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finishOpen = CountDownLatch(1)
        var observerStarted = false
        val startup = launch {
            prepareStartupConversationDatabase {
                entered.complete(Unit)
                check(finishOpen.await(10, TimeUnit.SECONDS))
            }
            observerStarted = true
        }
        try {
            withTimeout(10_000) { entered.await() }
            startup.cancel()
        } finally {
            finishOpen.countDown()
        }
        startup.join()
        assertTrue(startup.isCancelled)
        assertFalse(observerStarted)
    }
}
