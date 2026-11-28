package dev.chungjungsoo.gptmobile.data.context

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCapabilityCacheTest {
    @Test fun observationsDistinguishMissingExpiredInvalidatedAndExplicitRefreshWithoutChangingResults() = runTest {
        var now = 0L
        val cache = RouteCapabilityCache<String>({ now }, positiveTtl = 10)
        val observed = mutableListOf<RouteCapabilityCache.Observation>()
        assertEquals("first", cache.getOrLoad("route", observe = observed::add) { "first" })
        assertEquals("first", cache.getOrLoad("route", observe = observed::add) { error("Cache hit must not probe") })
        now = 10
        assertEquals("fresh", cache.getOrLoad("route", observe = observed::add) { "fresh" })
        assertEquals("current", cache.getOrLoad("route", isCurrent = { it == "current" }, observe = observed::add) { "current" })
        assertEquals("forced", cache.getOrLoad("route", forceRefresh = true, observe = observed::add) { "forced" })
        assertEquals(
            listOf(
                RouteCapabilityCache.Observation.MISS_ABSENT,
                RouteCapabilityCache.Observation.CACHE_HIT,
                RouteCapabilityCache.Observation.MISS_EXPIRED,
                RouteCapabilityCache.Observation.MISS_INVALIDATED,
                RouteCapabilityCache.Observation.FORCED_REFRESH
            ),
            observed
        )
        assertEquals("forced", cache.getOrLoad("route", observe = { error("Diagnostics unavailable") }) { error("Observer must not change cache admission") })
    }

    @Test fun invalidationDuringRefreshReportsDiscardedObservationAndNeverReturnsTheStaleValue() = runTest {
        val cache = RouteCapabilityCache<String>({ testScheduler.currentTime })
        val gate = CompletableDeferred<Unit>()
        val observed = mutableListOf<RouteCapabilityCache.Observation>()
        val pending = async {
            cache.getOrLoad("route", observe = observed::add) {
                gate.await()
                "stale"
            }
        }
        runCurrent()
        cache.invalidate()
        gate.complete(Unit)
        assertNull(pending.await())
        assertEquals(listOf(RouteCapabilityCache.Observation.REFRESH_INVALIDATED), observed)
        assertEquals("current", cache.getOrLoad("route") { "current" })
    }

    @Test fun boundedCacheAndLargeTtlKeepAValidLifetimeWithoutOverflow() = runTest {
        var now = Long.MAX_VALUE - 100
        val cache = RouteCapabilityCache<String>({ now }, positiveTtl = 1_000, maxEntries = 1)
        assertEquals("a", cache.getOrLoad("a") { "a" })
        now += 50
        assertEquals("a", cache.getOrLoad("a") { error("Overflow shortened the retained value's lifetime") })
        assertEquals("b", cache.getOrLoad("b") { "b" })
        assertEquals("refreshed-a", cache.getOrLoad("a") { "refreshed-a" })
    }

    @Test fun anAuditThatRequiresItsOwnFreshReceiptCannotBorrowAnOverlappingRefresh() = runTest {
        val cache = RouteCapabilityCache<String>({ testScheduler.currentTime })
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val ordinary = async {
            cache.getOrLoad("route", forceRefresh = true) {
                loads++
                gate.await()
                "ordinary"
            }
        }
        runCurrent()
        val audit = async {
            cache.getOrLoad("route", forceRefresh = true, shareConcurrentRefresh = false) {
                loads++
                "audit"
            }
        }
        runCurrent()
        gate.complete(Unit)
        assertEquals("ordinary", ordinary.await())
        assertEquals("audit", audit.await())
        assertEquals(2, loads)
        assertEquals("audit", cache.getOrLoad("route") { error("Fresh audit receipt was not retained") })
    }

    @Test fun explicitRepairRefreshWaitsForThisRouteAndReplacesItsStaleValueOnly() = runTest {
        val cache = RouteCapabilityCache<String>({ testScheduler.currentTime })
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val stale = async {
            cache.getOrLoad("route-a") {
                loads++
                gate.await()
                "missing"
            }
        }
        runCurrent()
        val repaired = async {
            cache.getOrLoad("route-a", forceRefresh = true) {
                loads++
                "ready"
            }
        }
        runCurrent()
        assertEquals("other", cache.getOrLoad("route-b") { "other" })
        gate.complete(Unit)
        assertEquals("missing", stale.await())
        assertEquals("ready", repaired.await())
        assertEquals("ready", cache.getOrLoad("route-a") { "unexpected reload" })
        assertEquals("other", cache.getOrLoad("route-b") { "unexpected reload" })
        assertEquals(2, loads)
    }

    @Test fun explicitRefreshRechecksANegativeEntryWithoutChangingOtherRoutes() = runTest {
        val cache = RouteCapabilityCache<String>({ testScheduler.currentTime })
        assertNull(cache.getOrLoad("route-a") { null })
        assertEquals("other", cache.getOrLoad("route-b") { "other" })
        assertEquals("ready", cache.getOrLoad("route-a", forceRefresh = true) { "ready" })
        assertEquals("other", cache.getOrLoad("route-b") { "unexpected reload" })
    }

    @Test fun overlappingExplicitRefreshesShareTheFreshObservation() = runTest {
        val cache = RouteCapabilityCache<String>({ testScheduler.currentTime })
        assertEquals("stale", cache.getOrLoad("route") { "stale" })
        val gate = CompletableDeferred<Unit>()
        val first = async {
            cache.getOrLoad("route", forceRefresh = true) {
                gate.await()
                "fresh"
            }
        }
        runCurrent()
        val second = async {
            cache.getOrLoad("route", forceRefresh = true) { error("Duplicate explicit refresh") }
        }
        runCurrent()
        gate.complete(Unit)
        assertEquals("fresh", first.await())
        assertEquals("fresh", second.await())
    }

    @Test fun sameRouteSingleFlightDoesNotBlockOtherRoutes() = runTest {
        val cache = RouteCapabilityCache<String>({ testScheduler.currentTime })
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val first = async {
            cache.getOrLoad("route-a") {
                loads++
                gate.await()
                "a"
            }
        }
        runCurrent()
        val second = async {
            cache.getOrLoad("route-a") {
                loads++
                "duplicate"
            }
        }
        assertEquals("b", cache.getOrLoad("route-b") { "b" })
        gate.complete(Unit)
        assertEquals("a", first.await())
        assertEquals("a", second.await())
        assertEquals(1, loads)
    }

    @Test fun invalidationRejectsInFlightOldRuntimeAndNegativeCacheExpires() = runTest {
        var time = 0L
        val cache = RouteCapabilityCache<String>({ time }, negativeTtl = 10)
        val gate = CompletableDeferred<Unit>()
        val stale = async {
            cache.getOrLoad("route") {
                gate.await()
                "old-model"
            }
        }
        runCurrent()
        cache.invalidate()
        gate.complete(Unit)
        assertNull(stale.await())
        assertNull(cache.getOrLoad("route") { null })
        assertNull(cache.getOrLoad("route") { "new-model" })
        time = 11
        assertEquals("new-model", cache.getOrLoad("route") { "new-model" })
    }
}
