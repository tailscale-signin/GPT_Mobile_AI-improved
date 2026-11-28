package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptionRouteCalibrationTest {
    @Test fun idleEditAfterDeviceAEndedBindsOnlyToTheNextObservedDeviceB() {
        val policy = VoiceCaptionRouteCalibration()
        val saved = mutableMapOf("A" to 90, "B" to -30)
        assertEquals(90, policy.observed("A") { saved.getValue("A") }.offsetMs)
        // A has ended. B connects while no AudioTrack is active; no route is inferred here.
        policy.request(180)
        policy.configureDefault(180)
        assertTrue(policy.hasPending)
        assertEquals(mapOf("A" to 90, "B" to -30), saved)
        val next = policy.observed("B") { saved.getValue("B") }
        assertEquals(VoiceCaptionRouteCalibration.Write("B", 180), next.write)
        next.write?.let { saved[it.routeKey] = it.offsetMs }
        assertFalse(policy.hasPending)
        assertEquals(mapOf("A" to 90, "B" to 180), saved)
        assertEquals(90, policy.observed("A") { saved.getValue("A") }.offsetMs)
    }

    @Test fun hydrationNeverOverwritesSavedRoutesAndFixedBenchmarksDoNotConsumeQueuedEdits() {
        val policy = VoiceCaptionRouteCalibration()
        policy.configureDefault(50)
        val restored = policy.observed("A") { 120 }
        assertEquals(120, restored.offsetMs)
        assertNull(restored.write)
        policy.request(200)
        val benchmark = VoiceCaptionCalibration()
        benchmark.fixed(0)
        assertFalse(benchmark.usesSavedRoutes)
        assertEquals(0, benchmark.resolve(50) { policy.observed("A") { 120 }.offsetMs })
        assertTrue(policy.hasPending)
        benchmark.useSavedRoutes()
        assertTrue(benchmark.usesSavedRoutes)
        assertEquals(200, benchmark.resolve(50) { policy.observed("B") { -80 }.offsetMs })
    }

    @Test fun rapidEditsUseTheLatestValueAndAsyncPersistenceCannotRestoreAnOldOffset() {
        val policy = VoiceCaptionRouteCalibration()
        policy.request(-90)
        policy.request(240)
        assertEquals(240, policy.observed("A") { 10 }.offsetMs)
        assertEquals(50, policy.observed("B") { 50 }.offsetMs)
        assertEquals(240, policy.observed("A") { 10 }.offsetMs)
        policy.request(600)
        assertEquals(500, policy.observed("B") { 50 }.offsetMs)
    }
}
