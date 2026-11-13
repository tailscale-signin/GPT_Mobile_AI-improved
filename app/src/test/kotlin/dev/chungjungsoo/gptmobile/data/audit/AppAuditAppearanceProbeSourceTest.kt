package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditAppearanceProbeSourceTest {
    @Test fun originAndContrastUseActualPresentationPoliciesWithFixtureEvidence() = runBlocking {
        val source = AppAuditAppearanceProbeSource()
        val plan = source.plan(AppAuditOptions())
        assertEquals(3, plan.size)
        for (entry in plan) {
            val observed = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No external operation") })
            assertEquals(entry.id, AppAuditOutcome.PASS, observed.outcome)
            assertEquals(AppAuditObservationSource.FIXTURE, observed.observationSource)
            assertTrue(observed.metrics.single().value >= 4)
        }
    }
}
