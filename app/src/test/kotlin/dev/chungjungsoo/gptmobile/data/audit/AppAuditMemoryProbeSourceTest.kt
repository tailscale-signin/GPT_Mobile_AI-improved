package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditMemoryProbeSourceTest {
    @Test fun memoryChecksExerciseDisposableProductionRecordsWithoutExternalDispatch() = runBlocking {
        val source = AppAuditMemoryProbeSource()
        val plan = source.plan(AppAuditOptions())
        assertEquals(3, plan.size)
        val results = plan.map { entry ->
            source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("Memory fixtures must not dispatch external work") })
        }
        results.forEach { result ->
            assertEquals(result.toString(), AppAuditOutcome.PASS, result.outcome)
            assertEquals(AppAuditObservationSource.FIXTURE, result.observationSource)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
            assertTrue(result.metrics.single().value >= 5)
        }
        assertEquals(listOf(15.0, 6.0, 5.0), results.map { it.metrics.single().value })
    }
}
