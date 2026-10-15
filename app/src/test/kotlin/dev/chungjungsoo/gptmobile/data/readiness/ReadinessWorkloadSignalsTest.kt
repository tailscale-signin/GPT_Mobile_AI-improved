package dev.chungjungsoo.gptmobile.data.readiness

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessWorkloadSignalsTest {
    @Test fun overlappingRunsReleasePriorityExactlyOnce() {
        val baseline = ReadinessWorkloadSignals.active.value
        val first = ReadinessWorkloadSignals.enter()
        val second = ReadinessWorkloadSignals.enter()
        assertEquals(baseline + 2, ReadinessWorkloadSignals.active.value)
        first.close()
        first.close()
        assertEquals(baseline + 1, ReadinessWorkloadSignals.active.value)
        second.close()
        assertEquals(baseline, ReadinessWorkloadSignals.active.value)
    }
}
