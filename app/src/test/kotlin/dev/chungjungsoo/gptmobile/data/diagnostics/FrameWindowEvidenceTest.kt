package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWindowEvidenceTest {
    @Test fun heapSamplesAndFrameDurationsStayInsideTheirActualOwnerObservationWindows() {
        val evidence = FrameWindowEvidence(heapSampleIntervalNanos = 100)
        evidence.observe(0, 7, setOf("a"), 1) { 50 }
        evidence.observe(10, 9, setOf("a", "b"), 1) { error("sample interval must bound runtime reads") }
        evidence.observe(100, 11, setOf("b"), 1) { 80 }
        evidence.observe(200, 500, emptySet(), 1) { 999 }
        val captured = evidence.snapshot(1)
        val a = run(captured, "a")
        val b = run(captured, "b")
        assertEquals(JsonPrimitive(50L), a["sampledProcessHeapPeakBytes"])
        assertEquals(JsonPrimitive(9L), a["frameDurationMaximumNs"])
        assertEquals(JsonPrimitive(10L), a["lastCallbackMonoNs"])
        assertEquals(JsonPrimitive(80L), b["sampledProcessHeapPeakBytes"])
        assertEquals(JsonPrimitive(2L), b["frameSamples"])
        assertEquals(JsonPrimitive(20L), b["frameDurationTotalNs"])
        assertEquals(JsonPrimitive(999L), captured.getValue("window").jsonObject["sampledProcessHeapPeakBytes"])
        assertEquals(JsonPrimitive(false), captured["causalAttribution"])
    }

    @Test fun unsampledShortRunNeverInheritsAnotherRunsOrLifetimeHeapPeak() {
        val evidence = FrameWindowEvidence(heapSampleIntervalNanos = 100)
        evidence.observe(0, 9, setOf("first"), 1) { 9000 }
        evidence.observe(10, 5, setOf("short"), 1) { error("no second heap sample") }
        val short = run(evidence.snapshot(1), "short")
        assertEquals(JsonPrimitive(1L), short["frameSamples"])
        assertEquals(JsonPrimitive(0L), short["heapSamples"])
        assertEquals(JsonNull, short["sampledProcessHeapPeakBytes"])
    }

    @Test fun privacyAndCaptureResetRemoveOldOwnersAndPeaksBeforePublication() {
        val evidence = FrameWindowEvidence()
        evidence.observe(10, 10, setOf("public-before"), 1) { 1000 }
        evidence.observe(20, 20, setOf("private"), null) { error("private capture cannot sample memory") }
        val cleared = evidence.snapshot(2)
        assertTrue((cleared["runs"] as JsonArray).isEmpty())
        assertEquals(JsonNull, cleared.getValue("window").jsonObject["sampledProcessHeapPeakBytes"])
        evidence.observe(30, 5, setOf("public-after"), 2) { 100 }
        assertEquals(JsonPrimitive(100L), run(evidence.snapshot(2), "public-after")["sampledProcessHeapPeakBytes"])
        assertTrue((evidence.snapshot(3)["runs"] as JsonArray).isEmpty())
    }

    @Test fun reportResetAndOwnerBoundsKeepEveryWindowFiniteAndHonest() {
        val evidence = FrameWindowEvidence(maximumRuns = 2)
        evidence.observe(10, 10, linkedSetOf("a", "b", "c"), 1) { 100 }
        val limited = evidence.snapshot(1)
        assertEquals(2, (limited["runs"] as JsonArray).size)
        assertEquals(JsonPrimitive(true), limited["runsTruncated"])
        evidence.clear()
        evidence.observe(30, 3, setOf("next"), 1) { 20 }
        val next = evidence.snapshot(1)
        assertEquals(JsonPrimitive(30L), next.getValue("window").jsonObject["firstCallbackMonoNs"])
        assertEquals(JsonPrimitive(20L), run(next, "next")["sampledProcessHeapPeakBytes"])
        assertFalse((next["runs"] as JsonArray).any { it.jsonObject["runId"] == JsonPrimitive("a") })
        assertEquals(JsonPrimitive(false), next["runsTruncated"])
    }

    @Test fun backwardsObservationCannotWidenTheMeasuredInterval() {
        val evidence = FrameWindowEvidence()
        evidence.observe(20, 5, setOf("run"), 1) { 30 }
        evidence.observe(10, 200, setOf("run"), 1) { error("backwards clock is invalid") }
        val captured = run(evidence.snapshot(1), "run")
        assertEquals(JsonPrimitive(1L), captured["frameSamples"])
        assertEquals(JsonPrimitive(20L), captured["lastCallbackMonoNs"])
    }

    @Test fun maximumOwnerMetadataFitsTheDurableLogMessageBoundary() {
        val evidence = FrameWindowEvidence(heapSampleIntervalNanos = 1)
        val owners = (0 until 16).mapTo(linkedSetOf()) { "$it".padEnd(128, 'a') }
        evidence.observe(Long.MAX_VALUE - 2, Long.MAX_VALUE, owners, 1) { Long.MAX_VALUE }
        evidence.observe(Long.MAX_VALUE - 1, Long.MAX_VALUE, owners, 1) { Long.MAX_VALUE }
        val message = "FRAME_WINDOW_EVIDENCE · coverageId=${Long.MAX_VALUE} · evidence=${evidence.snapshot(1)}"
        assertTrue("Structured frame evidence must survive the recorder's 7800-character boundary", message.length <= 7800)
        evidence.observe(Long.MAX_VALUE, 0, setOf("x".repeat(129)), 1) { 0 }
        assertEquals(JsonPrimitive(true), evidence.snapshot(1)["runsTruncated"])
    }

    private fun run(snapshot: JsonObject, id: String) = (snapshot["runs"] as JsonArray).map { it.jsonObject }.single { it["runId"] == JsonPrimitive(id) }
}
