package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditBehaviorProbeSourceTest {
    @Test fun actualBudgetAuthorityReceiptAndRunnerContractsPassWithoutExternalDispatch() = runBlocking {
        val source = AppAuditBehaviorProbeSource()
        val plan = source.plan(AppAuditOptions())
        assertEquals(5, plan.size)
        for (entry in plan) {
            val observed = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No network calls are authorized") })
            assertEquals(entry.id, AppAuditOutcome.PASS, observed.outcome)
            assertEquals(AppAuditObservationSource.FIXTURE, observed.observationSource)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observed.dispatchEvidence)
            assertTrue(observed.metrics.single().value > 0)
        }
    }
}
