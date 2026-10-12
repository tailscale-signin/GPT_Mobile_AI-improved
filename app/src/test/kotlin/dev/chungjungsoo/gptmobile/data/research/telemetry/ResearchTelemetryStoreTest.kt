package dev.chungjungsoo.gptmobile.data.research.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchTelemetryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun liveSuccessDoesNotConfuseEmptyCacheAndEvidenceYield() {
        val store = ResearchTelemetryStore(temporary.newFolder())
        assertNull(store.rollup("engine").successPercent)
        store.record(ResearchAttempt("a", "engine", "search", 1, 10, "EMPTY", evidenceRecords = 0))
        store.record(ResearchAttempt("b", "engine", "search", 2, 20, "SUCCESS", evidenceRecords = 3))
        store.record(ResearchAttempt("cached", "engine", "search", 3, 0, "SUCCESS", cacheHit = true))
        store.record(ResearchAttempt("skip", "engine", "search", 4, 0, "SKIPPED", dispatched = false))
        store.record(ResearchAttempt("b", "engine", "search", 2, 20, "SUCCESS", evidenceRecords = 3))
        val rollup = store.rollup("engine")
        assertEquals(2, rollup.attempts)
        assertEquals(100.0, rollup.successPercent!!, 0.0)
        assertEquals(3, rollup.usefulEvidenceRecords)
        assertEquals(1, rollup.cached)
        assertEquals(1, rollup.skipped)
        assertEquals(2L, rollup.lastSuccessAtMs)
        assertEquals(rollup, store.rollup("engine"))
    }

    @Test fun absentYieldMeasurementsRemainUnknownAndNewEventsInvalidateRollups() {
        val store = ResearchTelemetryStore(temporary.newFolder())
        store.record(ResearchAttempt("a", "engine", "search", 1, 10, "SUCCESS"))
        assertNull(store.rollup("engine").usefulEvidenceRecords)
        store.record(ResearchAttempt("b", "engine", "search", 2, 20, "FAILED"))
        assertEquals(2, store.rollup("engine").attempts)
        assertEquals(1, store.rollup("engine").failures)
    }
}
