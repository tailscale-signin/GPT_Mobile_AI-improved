package dev.chungjungsoo.gptmobile.data.context

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCapabilityCacheTest {
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
