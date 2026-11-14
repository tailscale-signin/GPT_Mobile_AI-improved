package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditGitHubProbeSourceTest {
    @Test fun planningBoundsConnectionsAndKeepsExternalExecutionUntested() = runBlocking {
        var reads = 0
        val targets = (1..3).map { index ->
            target("connection:$index").copy(read = { _, marker ->
                marker()
                reads++
                live()
            })
        }
        val source = AppAuditGitHubProbeSource({ AppAuditGitHubInventory(targets) }, { key -> targets.singleOrNull { it.key == key } })
        val plan = source.plan(AppAuditOptions(allowNetworkReads = true))
        assertEquals(12, plan.count { it.safetyClass == AppAuditSafetyClass.NETWORK_READ })
        assertEquals(4, plan.count { it.probeId in AppAuditGitHubCatalog.external.map { it.id } })
        assertEquals("omitted:1", plan.single { it.probeId == "github_target_limit_v1" }.targetId)
        assertEquals(0, reads)
        assertTrue(plan.filter { it.safetyClass == AppAuditSafetyClass.EXTERNAL_ONLY }.all { it.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED })
    }

    @Test fun networkOptOutDoesNotAdmitOrExecuteAnyRead() = runBlocking {
        var reads = 0
        var markers = 0
        val target = target().copy(read = { _, _ ->
            reads++
            live()
        })
        val source = source { target }
        val entry = source.plan(AppAuditOptions()).first()
        val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { markers++ })
        assertEquals(AppAuditOutcome.SKIPPED, result.outcome)
        assertEquals(0, reads)
        assertEquals(0, markers)
    }

    @Test fun absentConnectionAndRepositoryRemainExplicitPrerequisites() = runBlocking {
        val absent = AppAuditGitHubProbeSource({ AppAuditGitHubInventory(emptyList()) }, { null })
        val absentPlan = absent.plan(AppAuditOptions(allowNetworkReads = true))
        assertTrue(absentPlan.filter { it.safetyClass == AppAuditSafetyClass.NETWORK_READ }.all { it.prerequisite == AppAuditPrerequisite.NOT_CONFIGURED })
        val source = source { target().copy(repositorySelected = false) }
        val plan = source.plan(AppAuditOptions(allowNetworkReads = true))
        assertEquals(AppAuditPrerequisite.READY, plan.single { it.probeId == "github_live_account_v1" }.prerequisite)
        assertEquals(5, plan.count { it.reasonCode == "GITHUB_AUDIT_REPOSITORY_REQUIRED" })
    }

    @Test fun changedScopeOnEitherSideOfTheDurableMarkerCannotDispatch() = runBlocking {
        for (changeAtMarker in listOf(false, true)) {
            var reads = 0
            var selected = target().copy(read = { _, marker ->
                marker()
                reads++
                live()
            })
            val source = source { selected }
            val entry = source.plan(AppAuditOptions(allowNetworkReads = true)).first()
            if (!changeAtMarker) selected = selected.copy(revision = "b".repeat(64))
            val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { selected = selected.copy(revision = "b".repeat(64)) })
            assertTrue(result.configChanged)
            assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
            assertEquals(0, reads)
        }
    }

    @Test fun aPostDispatchScopeChangeRetainsEvidenceButCannotEarnAPass() = runBlocking {
        var selected = target()
        selected = selected.copy(read = { _, marker ->
            marker()
            selected = selected.copy(revision = "b".repeat(64))
            live()
        })
        val source = source { selected }
        val entry = source.plan(AppAuditOptions(allowNetworkReads = true)).first()
        val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) {})
        assertTrue(result.configChanged)
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
    }

    @Test fun recordedRateLimitsBlockWithoutAnyFurtherDispatch() = runBlocking {
        var reads = 0
        var selected = target().copy(read = { _, marker ->
            marker()
            reads++
            live()
        })
        val source = source { selected }
        val entry = source.plan(AppAuditOptions(allowNetworkReads = true)).first()
        selected = selected.copy(prerequisite = AppAuditPrerequisite.PERMISSION_REQUIRED, reasonCode = "GITHUB_AUDIT_RATE_LIMITED")
        val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No admission") })
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("GITHUB_AUDIT_RATE_LIMITED", result.reasonCode)
        assertFalse(result.configChanged)
        assertEquals(0, reads)
    }

    @Test fun admissionRevokedAfterAResponseRetainsTransportEvidenceWithoutGrantingAPass() = runBlocking {
        var selected = target()
        selected = selected.copy(read = { _, marker ->
            marker()
            selected = selected.copy(prerequisite = AppAuditPrerequisite.PERMISSION_REQUIRED, reasonCode = "GITHUB_AUDIT_RATE_LIMITED")
            live().copy(httpStatus = 200)
        })
        val source = source { selected }
        val entry = source.plan(AppAuditOptions(allowNetworkReads = true)).first()
        val result = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) {})
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("GITHUB_AUDIT_RATE_LIMITED", result.reasonCode)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
        assertEquals(AppAuditObservationSource.LIVE, result.observationSource)
        assertEquals(200, result.httpStatus)
        assertFalse(result.configChanged)
    }

    private fun source(target: () -> AppAuditGitHubTarget) = AppAuditGitHubProbeSource({ AppAuditGitHubInventory(listOf(target())) }, { key -> target().takeIf { it.key == key } })
    private fun target(key: String = "connection:fixture") = AppAuditGitHubTarget(key, "a".repeat(64), repositorySelected = true)
    private fun live() = AppAuditObservation(
        AppAuditOutcome.PASS,
        "GITHUB_AUDIT_READ_VERIFIED",
        evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
        observationSource = AppAuditObservationSource.LIVE,
        dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED
    )
}
