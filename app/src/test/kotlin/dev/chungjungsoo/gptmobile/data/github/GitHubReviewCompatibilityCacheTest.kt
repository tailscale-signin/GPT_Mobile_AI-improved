package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubReviewCompatibilityCacheTest {
    @Test fun aCachedFormatVerdictDoesNotStartAnotherProbeOrClaimItsDuration() = runTest {
        val cache = GitHubReviewCompatibility { testScheduler.currentTime }
        val observed = mutableListOf<GitHubReviewCompatibility.Observation>()
        var probes = 0
        assertEquals(
            GitHubReviewCompatibility.Result.Supported,
            cache.check("first-revision", observed::add) {
                probes++
                advanceTimeBy(250)
                GitHubReviewCompatibility.Result.Supported
            }
        )
        assertEquals(GitHubReviewCompatibility.Result.Supported, cache.check("first-revision", observed::add) { error("Cached verdict must not probe") })
        cache.check("changed-revision", observed::add) {
            probes++
            GitHubReviewCompatibility.Result.Supported
        }
        assertEquals(2, probes)
        assertEquals(listOf(RouteCapabilityCache.Observation.MISS_ABSENT, RouteCapabilityCache.Observation.CACHE_HIT, RouteCapabilityCache.Observation.MISS_ABSENT), observed.map { it.cache })
        assertEquals(listOf(true, false, true), observed.map { it.probeStarted })
        assertEquals(listOf(250L, 0L, 0L), observed.map { it.elapsedMillis })
        assertTrue(observed.all { it.outcome == GitHubReviewCompatibility.Outcome.SUPPORTED })
    }

    @Test fun concurrentChecksKeepSeparateObserversAndIdentifyTheOnePhysicalProbe() = runTest {
        val cache = GitHubReviewCompatibility { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val firstObservations = mutableListOf<GitHubReviewCompatibility.Observation>()
        val secondObservations = mutableListOf<GitHubReviewCompatibility.Observation>()
        val first = async {
            cache.check("revision", firstObservations::add) {
                gate.await()
                GitHubReviewCompatibility.Result.Supported
            }
        }
        runCurrent()
        val second = async { cache.check("revision", secondObservations::add) { error("Joined check must not start a second probe") } }
        runCurrent()
        advanceTimeBy(120)
        gate.complete(Unit)
        assertEquals(GitHubReviewCompatibility.Result.Supported, first.await())
        assertEquals(GitHubReviewCompatibility.Result.Supported, second.await())
        assertEquals(RouteCapabilityCache.Observation.MISS_ABSENT, firstObservations.single().cache)
        assertEquals(RouteCapabilityCache.Observation.JOINED_REFRESH, secondObservations.single().cache)
        assertTrue(firstObservations.single().probeStarted)
        assertFalse(secondObservations.single().probeStarted)
        assertEquals(120L, firstObservations.single().elapsedMillis)
        assertEquals(120L, secondObservations.single().elapsedMillis)
    }

    @Test fun unavailableAndCancelledChecksRemainUncachedAndDiagnosticErrorsCannotRejectACachedVerdict() = runTest {
        val cache = GitHubReviewCompatibility { testScheduler.currentTime }
        val observed = mutableListOf<GitHubReviewCompatibility.Observation>()
        val failure = ProviderEvent.Failed("The synthetic format request was unavailable.")
        assertEquals(GitHubReviewCompatibility.Result.Unavailable(failure), cache.check("revision", observed::add) { GitHubReviewCompatibility.Result.Unavailable(failure) })
        val gate = CompletableDeferred<Unit>()
        val cancelled = async {
            cache.check("revision", observed::add) {
                gate.await()
                GitHubReviewCompatibility.Result.Supported
            }
        }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        assertEquals(GitHubReviewCompatibility.Result.Supported, cache.check("revision", observed::add) { GitHubReviewCompatibility.Result.Supported })
        assertEquals(listOf(GitHubReviewCompatibility.Outcome.UNAVAILABLE, GitHubReviewCompatibility.Outcome.CANCELLED, GitHubReviewCompatibility.Outcome.SUPPORTED), observed.map { it.outcome })
        assertTrue(observed.all { it.cache == RouteCapabilityCache.Observation.MISS_ABSENT && it.probeStarted })
        assertEquals(GitHubReviewCompatibility.Result.Supported, cache.check("revision", observe = { error("Diagnostics unavailable") }) { error("Cached verdict must remain valid") })
    }

    @Test fun aCompletedNegativeVerdictExpiresBeforeTheNextProbe() = runTest {
        val cache = GitHubReviewCompatibility { testScheduler.currentTime }
        val observed = mutableListOf<GitHubReviewCompatibility.Observation>()
        assertEquals(GitHubReviewCompatibility.Result.UnsupportedFormat, cache.check("revision", observed::add) { GitHubReviewCompatibility.Result.UnsupportedFormat })
        assertEquals(GitHubReviewCompatibility.Result.UnsupportedFormat, cache.check("revision", observed::add) { error("Negative cache must retain its observed verdict") })
        advanceTimeBy(30_001)
        assertEquals(GitHubReviewCompatibility.Result.Supported, cache.check("revision", observed::add) { GitHubReviewCompatibility.Result.Supported })
        assertEquals(listOf(RouteCapabilityCache.Observation.MISS_ABSENT, RouteCapabilityCache.Observation.CACHE_HIT, RouteCapabilityCache.Observation.MISS_EXPIRED), observed.map { it.cache })
        assertEquals(listOf(GitHubReviewCompatibility.Outcome.UNSUPPORTED_FORMAT, GitHubReviewCompatibility.Outcome.UNSUPPORTED_FORMAT, GitHubReviewCompatibility.Outcome.SUPPORTED), observed.map { it.outcome })
    }
}
