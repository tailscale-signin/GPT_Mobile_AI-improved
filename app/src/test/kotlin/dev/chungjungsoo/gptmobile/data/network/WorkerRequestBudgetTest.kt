package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRequestBudgetTest {
    @Test fun `denied durable admission does not count as a physical request`() {
        val requests = AtomicInteger()
        val denied = WorkerRequestBudget(45000, requests, durableReservation = { false })
        assertTrue(runCatching { denied.acquireRequest() }.exceptionOrNull() is IOException)
        assertEquals(0, requests.get())
        val unavailable = WorkerRequestBudget(45000, requests, durableReservation = { throw IOException("Storage unavailable") })
        assertTrue(runCatching { unavailable.acquireRequest() }.exceptionOrNull() is IOException)
        assertEquals(0, requests.get())
        WorkerRequestBudget(45000, requests).acquireRequest()
        assertEquals(1, requests.get())
    }

    @Test fun `preflight records queue and preparation time without consuming a request`() {
        var time = 3000L
        val requests = AtomicInteger()
        val budget = WorkerRequestBudget(10000, requests, clock = { time }, queuedAtMillis = 1000)
        time = 7500
        val receipt = budget.preflight()
        assertEquals(2000L, receipt.queueAgeMillis)
        assertEquals(4500L, receipt.elapsedMillis)
        assertEquals(5500L, receipt.remainingMillis)
        assertEquals(0, requests.get())
        assertEquals("not_implied_by_local_cancellation", receipt.remoteCleanupConfirmation)
        time = 12500
        assertTrue(runCatching { budget.preflight() }.exceptionOrNull()?.message.orEmpty().contains("500 ms remain"))
        assertEquals(0, requests.get())
    }

    @Test fun `physical ceiling is shared across worker and repair requests`() {
        val requests = AtomicInteger()
        val first = WorkerRequestBudget(45000, requests, requestLimit = 2)
        val second = WorkerRequestBudget(45000, requests, requestLimit = 2)
        first.acquireRequest()
        second.acquireRequest()
        assertTrue(runCatching { second.acquireRequest() }.exceptionOrNull() is IOException)
        assertEquals(2, requests.get())
    }

    @Test fun `deadline prevents another request from starting`() {
        var time = 0L
        val requests = AtomicInteger()
        val budget = WorkerRequestBudget(45000, requests, clock = { time })
        time = 44500
        assertTrue(runCatching { budget.acquireRequest() }.isFailure)
        assertEquals(0, requests.get())
    }

    @Test fun `coordinator retries cannot multiply into provider retries`() = runTest {
        var attempts = 0
        withContext(WorkerRequestBudget(45000, AtomicInteger())) {
            val failure = runCatching {
                ResilientStreamingClient.executeWithRetry<Unit> {
                    attempts++
                    throw IOException("Connect timeout")
                }
            }
            assertTrue(failure.isFailure)
        }
        assertEquals(1, attempts)
    }
}
