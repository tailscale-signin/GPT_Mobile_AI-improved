package dev.chungjungsoo.gptmobile.data.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
