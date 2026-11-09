package dev.chungjungsoo.gptmobile.data.permissions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGrantPersistenceTest {
    @Test fun concurrentReadModifyWritesAreSerialized() = runBlocking {
        val value = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        (1..12).map {
            async {
                ToolGrantPersistence.mutate {
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    try {
                        val previous = value.get()
                        Thread.sleep(5)
                        value.set(previous + 1)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }.awaitAll()
        assertEquals(12, value.get())
        assertEquals(1, maximum.get())
    }

    @Test fun mutationRunsOffTheCallerAndDoesNotFinishBeforeDurableWrite() = runBlocking {
        val caller = Thread.currentThread()
        val entered = CompletableDeferred<Thread>()
        val allowCommit = CountDownLatch(1)
        val mutation = async {
            ToolGrantPersistence.mutate {
                entered.complete(Thread.currentThread())
                check(allowCommit.await(5, TimeUnit.SECONDS))
                "committed"
            }
        }
        try {
            assertNotSame(caller, withTimeout(5000) { entered.await() })
            assertFalse(mutation.isCompleted)
        } finally {
            allowCommit.countDown()
        }
        assertEquals("committed", withTimeout(5000) { mutation.await() })
    }

    @Test fun aSecondMutationWaitsForTheFirstAndFailureIsNotReportedAsSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val allowCommit = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = async {
            runCatching {
                ToolGrantPersistence.mutate {
                    order += "first-start"
                    entered.complete(Unit)
                    check(allowCommit.await(5, TimeUnit.SECONDS))
                    order += "first-failed"
                    throw IllegalStateException("Could not persist revocation")
                }
            }
        }
        withTimeout(5000) { entered.await() }
        val second = async {
            ToolGrantPersistence.mutate { order += "second-committed" }
        }
        allowCommit.countDown()
        assertTrue(withTimeout(5000) { first.await() }.exceptionOrNull() is IllegalStateException)
        withTimeout(5000) { second.await() }
        assertEquals(listOf("first-start", "first-failed", "second-committed"), order)
    }
}
