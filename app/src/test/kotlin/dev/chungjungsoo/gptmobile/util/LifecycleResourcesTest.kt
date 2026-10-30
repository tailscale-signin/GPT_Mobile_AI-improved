package dev.chungjungsoo.gptmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleResourcesTest {
    @Test fun negativeMonotonicClockOriginStillHonorsLeaseLifetime() {
        var now = -100L
        var closed = 0
        val resources = LifecycleResources { now }
        resources.acquire("lease", 20) { closed++ }
        now = -81
        resources.cleanupExpired()
        assertEquals(0, closed)
        now = -80
        resources.cleanupExpired()
        assertEquals(1, closed)
        resources.close()
        assertEquals(1, closed)
    }

    @Test fun replacementExpiryAndDisposalReleaseExactlyOnce() {
        var time = 0L
        var released = 0
        val resources = LifecycleResources { time }
        val first = resources.acquire("key", 10) { released++ }
        val second = resources.acquire("key", 20) { released++ }
        first.close()
        assertEquals(1, released)
        time = 21
        resources.cleanupExpired()
        second.close()
        resources.close()
        resources.close()
        assertEquals(2, released)
        assertTrue(runCatching { resources.acquire("late") {} }.isFailure)
    }

    @Test fun oneFailingCleanupDoesNotLeakOtherResources() {
        var released = false
        val resources = LifecycleResources()
        resources.acquire("bad") { error("failure") }
        resources.acquire("good") { released = true }
        resources.close()
        assertTrue(released)
    }
}
