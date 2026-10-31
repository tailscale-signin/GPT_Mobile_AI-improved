package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun canceledChatWaiterDoesNotCorruptReaderCount() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val benchmark = launch {
            InferenceAdmission.benchmark {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waitingChat = launch { InferenceAdmission.shared { error("Must not acquire") } }
        yield()
        waitingChat.cancel()
        waitingChat.join()
        release.complete(Unit)
        benchmark.join()
        kotlinx.coroutines.withTimeout(2000) { InferenceAdmission.benchmark { } }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResourceAwareInferenceAdmissionTest {
    @Test
    fun saturatedQueueRejectsBeforeDispatchAndCancellationFreesCapacity() = kotlinx.coroutines.test.runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val owner = launch {
            InferenceAdmission.shared(ResourceRequest.local("owner")) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiting = (1..InferenceAdmission.MAX_WAITING).map { index -> launch { InferenceAdmission.shared(ResourceRequest.local("queued-$index")) { } } }
        runCurrent()
        var dispatched = false
        val failure = runCatching { InferenceAdmission.shared(ResourceRequest.local("overflow")) { dispatched = true } }.exceptionOrNull()
        assertTrue(failure is AdmissionCapacityException)
        assertFalse(dispatched)
        org.junit.Assert.assertEquals(InferenceAdmission.MAX_WAITING, InferenceAdmission.blockers.value.size)
        var independentRan = false
        InferenceAdmission.shared(ResourceRequest.remote("independent", "other-endpoint")) { independentRan = true }
        assertTrue(independentRan)
        waiting.first().cancel()
        waiting.first().join()
        val replacement = launch { InferenceAdmission.shared(ResourceRequest.local("replacement")) { dispatched = true } }
        runCurrent()
        assertTrue(replacement.isActive)
        assertFalse(dispatched)
        release.complete(Unit)
        owner.join()
        waiting.forEach { it.join() }
        replacement.join()
        assertTrue(dispatched)
        assertTrue(InferenceAdmission.blockers.value.isEmpty())
        InferenceAdmission.benchmark { }
    }

    @Test
    fun queuedResearchSurvivesOldSixtySecondDeadlineAndRunsAtTrialBoundary() = kotlinx.coroutines.test.runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val trial = launch {
            InferenceAdmission.benchmark {
                entered.complete(Unit)
                release.await()
                order += "checkpoint"
            }
        }
        entered.await()
        val states = mutableListOf<AdmissionOutcome>()
        val research = launch {
            InferenceAdmission.shared(ResourceRequest.remote("research", "public-endpoint"), { states += it }) { order += "research" }
        }
        runCurrent()
        advanceTimeBy(90_000)
        assertTrue(research.isActive)
        assertTrue(states.single() is AdmissionOutcome.Waiting)
        val nextTrial = launch { InferenceAdmission.benchmark { order += "next_trial" } }
        runCurrent()
        release.complete(Unit)
        trial.join()
        research.join()
        nextTrial.join()
        org.junit.Assert.assertEquals(listOf("checkpoint", "research", "next_trial"), order)
    }

    @Test
    fun independentCloudWorkDoesNotNeedLocalResource() = kotlinx.coroutines.test.runTest {
        val release = CompletableDeferred<Unit>()
        val localEntered = CompletableDeferred<Unit>()
        val local = launch {
            InferenceAdmission.shared(ResourceRequest.local("local")) {
                localEntered.complete(Unit)
                release.await()
            }
        }
        localEntered.await()
        var remoteRan = false
        InferenceAdmission.shared(ResourceRequest.remote("remote", "endpoint")) { remoteRan = true }
        assertTrue(remoteRan)
        release.complete(Unit)
        local.join()
    }

    @Test
    fun localResourceRemainsExclusiveWithoutBenchmark() = kotlinx.coroutines.test.runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val first = launch {
            InferenceAdmission.shared(ResourceRequest.local("first")) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var secondRan = false
        val second = launch { InferenceAdmission.shared(ResourceRequest.local("second")) { secondRan = true } }
        runCurrent()
        assertFalse(secondRan)
        second.cancel()
        second.join()
        release.complete(Unit)
        first.join()
        InferenceAdmission.shared(ResourceRequest.local("third", 2)) { assertFalse(secondRan) }
    }

    @Test
    fun explicitAdmissionDeadlineIsNotProviderFailureAndDoesNotLeakLease() = kotlinx.coroutines.test.runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val trial = launch {
            InferenceAdmission.benchmark {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var dispatched = false
        val outcome = async {
            runCatching { InferenceAdmission.shared(ResourceRequest(ownerId = "deadline", queueTimeoutMillis = 100)) { dispatched = true } }
        }
        advanceTimeBy(101)
        assertTrue(outcome.await().exceptionOrNull() is AdmissionExpiredException)
        assertFalse(dispatched)
        release.complete(Unit)
        trial.join()
        InferenceAdmission.benchmark { }
    }

    @Test
    fun measuredOwnerCanAcquireRealNestedLocalLease() = kotlinx.coroutines.test.runTest {
        InferenceAdmission.benchmark {
            InferenceAdmission.admit(ResourceRequest.local("measured-local")) { lease ->
                assertTrue(ResourceRequest.LOCAL_RUNTIME in lease.resourceKeys)
                InferenceAdmission.shared(ResourceRequest.local("nested")) { }
            }
        }
        assertFalse(InferenceAdmission.isExclusiveBusy)
    }
}
