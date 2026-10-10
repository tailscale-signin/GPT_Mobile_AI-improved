package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceAdmissionTest {
    @Test
    fun benchmarkWaitsForChatAndAllowsItsOwnNestedInference() = runBlocking {
        val enteredChat = CompletableDeferred<Unit>()
        val releaseChat = CompletableDeferred<Unit>()
        val enteredBenchmark = CompletableDeferred<Unit>()
        val chat = launch {
            InferenceAdmission.shared {
                enteredChat.complete(Unit)
                releaseChat.await()
            }
        }
        enteredChat.await()
        val benchmark = launch { InferenceAdmission.benchmark { InferenceAdmission.shared { enteredBenchmark.complete(Unit) } } }
        yield()
        assertFalse(enteredBenchmark.isCompleted)
        releaseChat.complete(Unit)
        benchmark.join()
        chat.join()
        assertTrue(enteredBenchmark.isCompleted)
    }

    @Test
    fun canceledBenchmarkWaiterDoesNotBlockNewChat() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val chat = launch {
            InferenceAdmission.shared {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiting = launch { InferenceAdmission.benchmark { error("Must not acquire") } }
        yield()
        waiting.cancel()
        waiting.join()
        release.complete(Unit)
        chat.join()
        var resumed = false
        InferenceAdmission.shared { resumed = true }
        assertTrue(resumed)
    }
}
