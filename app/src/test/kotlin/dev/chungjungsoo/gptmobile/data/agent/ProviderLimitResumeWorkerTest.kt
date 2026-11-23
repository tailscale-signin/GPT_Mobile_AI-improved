package dev.chungjungsoo.gptmobile.data.agent

import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderLimitResumeWorkerTest {
    @Test fun workerIsConfirmedOnlyAfterItsAsynchronousEnqueueCommits() = runTest {
        for (success in listOf(true, false)) {
            val future = EnqueueFuture()
            val operation = mockk<Operation>()
            every { operation.result } returns future
            val result = async {
                persistProviderWaitSafely(
                    persist = { true },
                    observeOwned = { error("successful commit must not be reinterpreted") },
                    enqueue = { ProviderLimitResumeWorker.confirmEnqueue(operation) }
                )
            }
            runCurrent()
            assertFalse(result.isCompleted)
            if (success) future.complete() else future.fail()
            val observed = result.await()
            assertTrue(observed.waiting)
            assertEquals(if (success) "ENQUEUED" else "PENDING_RECONCILIATION", observed.schedulerState)
        }
    }

    @Test fun unconfirmedEnqueueTimesOutWithoutCancellingOrReplayingDurableWork() = runTest {
        val future = EnqueueFuture()
        val operation = mockk<Operation>()
        every { operation.result } returns future
        var writes = 0
        val result = persistProviderWaitSafely(
            persist = {
                writes++
                true
            },
            observeOwned = { error("already committed") },
            enqueue = { ProviderLimitResumeWorker.confirmEnqueue(operation) }
        )
        assertEquals(1, writes)
        assertTrue(result.waiting)
        assertEquals("PENDING_RECONCILIATION", result.schedulerState)
        assertFalse(future.isCancelled)
        future.complete()
        assertEquals("PENDING_RECONCILIATION", result.schedulerState)
    }

    private class EnqueueFuture : ListenableFuture<Operation.State.SUCCESS> {
        private val future = CompletableFuture<Operation.State.SUCCESS>()
        fun complete() {
            future.complete(Operation.SUCCESS)
        }
        fun fail() {
            future.completeExceptionally(IOException("asynchronous scheduler failure"))
        }
        override fun addListener(listener: Runnable, executor: Executor) {
            future.whenComplete { _, _ -> executor.execute(listener) }
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = future.cancel(mayInterruptIfRunning)
        override fun isCancelled(): Boolean = future.isCancelled
        override fun isDone(): Boolean = future.isDone
        override fun get(): Operation.State.SUCCESS = future.get()
        override fun get(timeout: Long, unit: TimeUnit): Operation.State.SUCCESS = future.get(timeout, unit)
    }
}
