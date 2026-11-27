package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubRequestSchedulerStressTest {
    @Test fun physicalRetriesCountAsOneLogicalCircuitFailure() = runTest {
        val scheduler = GitHubRequestScheduler()
        withContext(GitHubReadRetry.Scope()) {
            repeat(3) {
                assertTrue(runCatching { scheduler.execute<String>("https://api.github.com/repos/o/r") { throw IOException("DNS") } }.isFailure)
            }
        }
        val core = scheduler.metrics()["circuits"]!!.jsonObject["core"]!!.jsonObject
        assertEquals("CLOSED", core["state"]!!.jsonPrimitive.content)
        assertEquals(1, core["consecutive_transient_failures"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun searchCircuitDoesNotPoisonCoreRepositoryReads() = runTest {
        val scheduler = GitHubRequestScheduler()
        repeat(3) {
            withContext(GitHubReadRetry.Scope()) {
                runCatching { scheduler.execute<String>("https://api.github.com/search/code?q=x") { throw IOException("DNS") } }
            }
        }
        assertTrue(runCatching { scheduler.execute<String>("https://api.github.com/search/issues?q=x") { "blocked" } }.exceptionOrNull() is GitHubRequestCircuit.Open)
        assertEquals("core-ok", scheduler.execute("https://api.github.com/repos/o/r") { "core-ok" })
        assertTrue(scheduler.transportRetryAfterMillis("search_code") > 0)
        assertEquals(0L, scheduler.transportRetryAfterMillis("repository"))
        val circuits = scheduler.metrics()["circuits"]!!.jsonObject
        assertEquals("OPEN", circuits["search"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("CLOSED", circuits["core"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test fun thousandIdenticalReadsHaveBoundedSubscribersAndOnePhysicalExecution() = runTest {
        val scheduler = GitHubRequestScheduler(maxJoined = 16)
        val held = CompletableDeferred<Unit>()
        var executions = 0
        val owner = async {
            scheduler.execute("immutable") {
                executions++
                held.await()
                "bytes"
            }
        }
        runCurrent()
        val readers = (1..1000).map {
            async { runCatching { scheduler.execute<String>("immutable") { error("Duplicate read") } } }
        }
        runCurrent()
        assertEquals(16, scheduler.metrics()["waiting_shared_reads"]!!.jsonPrimitive.intOrNull)
        assertEquals(984, scheduler.metrics()["capacity_rejections"]!!.jsonPrimitive.intOrNull)
        held.complete(Unit)
        assertEquals("bytes", owner.await())
        val results = readers.awaitAll()
        assertEquals(16, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull()?.message?.startsWith("GITHUB_WAITING_CAPACITY:") == true })
        assertEquals(1, executions)
        assertEquals(0, scheduler.metrics()["waiting_shared_reads"]!!.jsonPrimitive.intOrNull)
        assertEquals(0, scheduler.metrics()["active_requests"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun cancelledSubscriberReturnsItsSlotWithoutCancellingOwner() = runTest {
        val scheduler = GitHubRequestScheduler(maxJoined = 1)
        val held = CompletableDeferred<Unit>()
        val owner = async {
            scheduler.execute("blob") {
                held.await()
                "result"
            }
        }
        runCurrent()
        val subscriber = async { scheduler.execute<String>("blob") { error("Duplicate read") } }
        runCurrent()
        subscriber.cancelAndJoin()
        assertFalse(owner.isCancelled)
        val replacement = async { scheduler.execute<String>("blob") { error("Duplicate read") } }
        runCurrent()
        held.complete(Unit)
        assertEquals(owner.await(), replacement.await())
        assertEquals(0, scheduler.metrics()["waiting_shared_reads"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun admittedBackgroundWorkCannotBeStarvedByVisibleRequests() = runTest {
        val scheduler = GitHubRequestScheduler(concurrency = 1, maxPriorityBypasses = 2)
        val held = CompletableDeferred<Unit>()
        val first = async { scheduler.execute { held.await() } }
        runCurrent()
        val order = mutableListOf<String>()
        val background = async { scheduler.execute(priority = Int.MIN_VALUE) { order += "background" } }
        val visible = (1..3).map { index -> async { scheduler.execute(priority = Int.MAX_VALUE) { order += "visible-$index" } } }
        runCurrent()
        held.complete(Unit)
        first.await()
        background.await()
        visible.awaitAll()
        assertEquals(listOf("visible-1", "visible-2", "background", "visible-3"), order)
    }

    @Test fun cancellationAfterGrantReleasesThePermitForTheNextRequest() = runTest {
        val scheduler = GitHubRequestScheduler(concurrency = 1)
        val held = CompletableDeferred<Unit>()
        val owner = async { scheduler.execute { held.await() } }
        runCurrent()
        var cancelledExecuted = false
        val cancelled = async { scheduler.execute { cancelledExecuted = true } }
        val next = async { scheduler.execute { "next" } }
        runCurrent()
        owner.invokeOnCompletion { cancelled.cancel() }
        held.complete(Unit)
        owner.await()
        cancelled.cancelAndJoin()
        assertEquals("next", next.await())
        assertFalse(cancelledExecuted)
        assertEquals(0, scheduler.metrics()["active_requests"]!!.jsonPrimitive.intOrNull)
        assertEquals(0, scheduler.metrics()["queued_requests"]!!.jsonPrimitive.intOrNull)
    }
}
