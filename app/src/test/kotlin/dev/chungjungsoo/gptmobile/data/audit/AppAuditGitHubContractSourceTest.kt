package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditGitHubContractSourceTest {
    @Test fun allEightCategoriesExerciseProductionContractsWithoutNetworkAuthority() = runBlocking {
        val source = AppAuditGitHubContractSource()
        val plan = source.plan(AppAuditOptions())
        assertEquals(16, plan.size)
        assertEquals(AppAuditGitHubCategory.entries.toSet(), plan.mapNotNull(AppAuditGitHubCatalog::category).toSet())
        for (entry in plan) {
            val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No network authority") })
            assertEquals(entry.probeId, AppAuditOutcome.PASS, result.outcome)
            assertEquals(AppAuditEvidenceKind.LOCAL_CONTRACT, result.evidenceKind)
            assertEquals(AppAuditObservationSource.FIXTURE, result.observationSource)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
            assertTrue(result.metrics.single().value >= 3)
        }
    }

    @Test fun aChangedFixtureCannotReuseItsPreviousResult() = runBlocking {
        val source = AppAuditGitHubContractSource()
        val entry = source.plan(AppAuditOptions()).first().copy(fixtureHash = "changed")
        val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No network authority") })
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertTrue(result.configChanged)
    }
}
