package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCaptureTimingsTest {
    @Test fun failuresAndCancellationRetainOnlyTheObservedPhaseTime() = runBlocking {
        var now = 100L
        val timings = DiagnosticCaptureTimings { now }
        runCatching {
            timings.measure(DiagnosticCaptureTimings.Phase.QUERY_MATERIALIZATION) {
                now += 17
                error("private query must not be retained")
            }
        }
        runCatching {
            timings.measureSuspending(DiagnosticCaptureTimings.Phase.PROTECTED_HYDRATION) {
                now += 29
                throw CancellationException("protected payload")
            }
        }
        val snapshot = timings.json()
        val phases = snapshot.getValue("phases").jsonObject
        assertEquals(17L, phases.getValue("QUERY_MATERIALIZATION").jsonObject.getValue("durationNanos").jsonPrimitive.long)
        assertEquals(29L, phases.getValue("PROTECTED_HYDRATION").jsonObject.getValue("durationNanos").jsonPrimitive.long)
        assertEquals(1L, phases.getValue("QUERY_MATERIALIZATION").jsonObject.getValue("calls").jsonPrimitive.long)
        assertEquals(0L, phases.getValue("CHECKPOINT").jsonObject.getValue("calls").jsonPrimitive.long)
        assertTrue("private query" !in snapshot.toString() && "protected payload" !in snapshot.toString())
    }
}
