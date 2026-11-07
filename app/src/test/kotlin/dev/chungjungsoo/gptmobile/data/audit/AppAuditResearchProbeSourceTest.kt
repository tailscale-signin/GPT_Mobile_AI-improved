package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppAuditResearchProbeSourceTest {
    @Test fun realResearchProjectionRetainsPublicReceiptsWithoutSharingPrivateFixtureContext() = runBlocking {
        val source = AppAuditResearchProbeSource()
        val entry = source.plan(AppAuditOptions()).single()
        val observed = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No external operation") })
        assertEquals(AppAuditOutcome.PASS, observed.outcome)
        assertEquals(AppAuditObservationSource.FIXTURE, observed.observationSource)
        assertEquals(5.0, observed.metrics.single().value, 0.0)
        assertFalse(observed.toString().contains("app-audit-private-canary"))
    }
}
