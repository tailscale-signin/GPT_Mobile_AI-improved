package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticExportMemoryIntervalTest {
    private val before = DiagnosticExportMemory(100, 1000, 700, 500, 500, gcCount = 4, gcTimeMillis = 20, allocatedBytes = 4000, freedBytes = 3500, nativeGcSampledAtMillis = 90)

    @Test fun stageIntervalLabelsProcessWideCountersAndPreservesSignedHeapChanges() {
        val after = before.copy(capturedAtMillis = 200, nativeGcSampledAtMillis = 190, javaUsedBytes = 450, gcCount = 5, gcTimeMillis = 23, allocatedBytes = 4500, freedBytes = 4050)
        val interval = after.intervalSince(before)
        assertEquals(JsonPrimitive("PROCESS_WIDE_DURING_STAGE"), interval["measurementScope"])
        assertEquals(JsonPrimitive("CORRELATED_NOT_EXCLUSIVE"), interval["attribution"])
        assertEquals(JsonPrimitive(500), interval["allocatedDeltaBytes"])
        assertEquals(JsonPrimitive(550), interval["freedDeltaBytes"])
        assertEquals(JsonPrimitive(-50), interval["javaUsedDeltaBytes"])
        assertEquals(JsonPrimitive(1), interval["gcCountDelta"])
        assertEquals(JsonPrimitive(3), interval["gcTimeDeltaMillis"])
    }

    @Test fun cachedOrResetCountersAreUnavailableInsteadOfFalseZeroOrNegativeUsage() {
        val cached = before.copy(capturedAtMillis = 110).intervalSince(before)
        assertEquals(JsonPrimitive(false), cached["freshCounterSamples"])
        assertEquals(JsonNull, cached["allocatedDeltaBytes"])
        val reset = before.copy(capturedAtMillis = 200, nativeGcSampledAtMillis = 190, allocatedBytes = 3, gcCount = null).intervalSince(before)
        assertEquals(JsonPrimitive(true), reset["counterReset"])
        assertEquals(JsonNull, reset["allocatedDeltaBytes"])
        assertEquals(JsonNull, reset["gcCountDelta"])
    }
}
