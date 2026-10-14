package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedAssistWorkTest {
    private fun subscriber(allowed: suspend () -> Boolean = { true }, status: suspend (AssistTaskState) -> Unit = {}) =
        SharedAssistWork.Subscriber(allowed, status, {})

    @Test fun cancellingFirstConsumerKeepsOneProducerForSecondConsumer() = runTest {
        val work = SharedAssistWork<String>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val producer: suspend (SharedAssistWork<String>.Flight) -> String = {
            calls++
            release.await()
            "retained artifact"
        }
        val first = async { work.await("same-authority", subscriber(), producer) }
        runCurrent()
        val second = async { work.await("same-authority", subscriber(), producer) }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals("retained artifact", second.await())
        assertEquals(1, calls)
    }

    @Test fun revokedSubscriberGetsNoLateArtifactAndLastSubscriberStopsProducer() = runTest {
        val work = SharedAssistWork<String>()
        var allowed = true
        var stopped = false
        val first = async {
            work.await("key", subscriber(allowed = { allowed })) {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        allowed = false
        advanceTimeBy(101)
        runCurrent()
        assertNull(first.await())
        assertTrue(stopped)
    }

    @Test fun closedConsumerChannelCannotDiscardOtherConsumersStatusOrArtifact() = runTest {
        val work = SharedAssistWork<String>()
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<AssistTaskState>()
        val producer: suspend (SharedAssistWork<String>.Flight) -> String = { flight ->
            release.await()
            flight.publish(AssistTaskState(taskId = "shared-task", producerInvocationId = "physical-invocation"))
            "artifact"
        }
        val first = async { work.await("key", subscriber(status = { throw ClosedSendChannelException("ended") }), producer) }
        runCurrent()
        val second = async { work.await("key", subscriber(status = { delivered += it }), producer) }
        runCurrent()
        release.complete(Unit)
        assertEquals("artifact", second.await())
        assertNull(first.await())
        assertEquals("physical-invocation", delivered.single().producerInvocationId)
    }

    @Test fun differentPermissionScopesNeverShareProducer() = runTest {
        val work = SharedAssistWork<String>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val producer: suspend (SharedAssistWork<String>.Flight) -> String = {
            calls++
            release.await()
            "artifact"
        }
        val first = async { work.await("scope-a", subscriber(), producer) }
        val second = async { work.await("scope-b", subscriber(), producer) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, calls)
    }
}
