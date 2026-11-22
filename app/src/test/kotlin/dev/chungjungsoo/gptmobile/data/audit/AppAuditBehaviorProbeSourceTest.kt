package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditBehaviorProbeSourceTest {
    @Test fun actualBudgetAuthorityReceiptAndRunnerContractsPassWithoutExternalDispatch() = runBlocking {
        val source = AppAuditBehaviorProbeSource()
        val plan = source.plan(AppAuditOptions())
        val expectedAssertions = mapOf("budget_precheck_v1" to 4, "authorization_denial_v1" to 2, "unknown_receipt_v1" to 2, "grant_scope_v1" to 5, "runner_isolation_v1" to 5)
        assertEquals(5, plan.size)
        for (entry in plan) {
            val observed = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No network calls are authorized") })
            assertEquals(entry.id, AppAuditOutcome.PASS, observed.outcome)
            assertEquals(AppAuditObservationSource.FIXTURE, observed.observationSource)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observed.dispatchEvidence)
            assertTrue(observed.metrics.single().value > 0)
            val evidence = checkNotNull(observed.fixtureEvidence)
            assertEquals(expectedAssertions[entry.probeId], evidence.attempted)
            assertEquals(evidence.attempted, evidence.passed)
            assertTrue(evidence.assertions.none { it.code == AppAuditAssertionCode.FIXTURE_ASSERTION || it.blocked })
            assertEquals((1..evidence.attempted).toList(), evidence.assertions.map { it.ordinal })
        }
    }
}
