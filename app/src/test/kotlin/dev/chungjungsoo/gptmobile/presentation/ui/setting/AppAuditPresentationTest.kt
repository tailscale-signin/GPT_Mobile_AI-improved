package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditDispatchEvidence
import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPrerequisite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditPresentationTest {
    @Test fun measuredScoreCannotHideUnexercisedConfiguredCachedOrBlockedInventory() {
        val passed = auditEntry("local-pass", weight = 3)
        val failed = auditEntry("live-fail", AppAuditEvidenceKind.LIVE_METADATA, "gateway")
        val configured = auditEntry("configured", AppAuditEvidenceKind.CONFIGURATION)
        val cached = auditEntry("cached", AppAuditEvidenceKind.LIVE_METADATA, "plugins")
        val blocked = auditEntry("blocked")
        val external = auditEntry("external", AppAuditEvidenceKind.EXTERNAL_SUITE, "ui").copy(prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED)
        val pending = auditEntry("pending")
        val canceled = auditEntry("canceled")
        val plan = listOf(passed, failed, configured, cached, blocked, external, pending, canceled)
        val result = appAuditPresentation(
            auditRun(
                plan,
                listOf(
                    auditResult(passed),
                    auditResult(failed, AppAuditOutcome.FAIL, AppAuditObservationSource.LIVE),
                    auditResult(configured, source = AppAuditObservationSource.CONFIGURATION),
                    auditResult(cached, source = AppAuditObservationSource.CACHE),
                    auditResult(blocked, AppAuditOutcome.BLOCKED, attempted = false),
                    auditResult(external, AppAuditOutcome.UNTESTED, attempted = false),
                    auditResult(canceled, AppAuditOutcome.CANCELED)
                )
            )
        )
        assertEquals(75.0, result.score.score!!, 0.0)
        assertEquals("Passing weight 3 / measured weight 4", result.weightText)
        assertEquals("Exercised 2/8 planned checks · Verified passes 1/8 planned checks", result.coverageText)
        assertTrue(result.countsText.contains("Passed 3 · Failed 1 · Blocked 1"))
        assertTrue(result.countsText.contains("Untested or pending 2"))
        assertTrue(result.countsText.contains("Canceled 1"))
        assertTrue(result.coverageNotice.startsWith("Partial coverage: 6"))
        assertEquals(8, result.components.sumOf { it.entries.size })
        assertFalse(result.components.flatMap { it.entries }.single { it.entry.id == cached.id }.includedInScore)
    }

    @Test fun configurationOnlyRunHasNoNumericScoreAndKeepsEveryAcceptedCheck() {
        val configured = auditEntry("configured", AppAuditEvidenceKind.CONFIGURATION)
        val unavailable = auditEntry("unavailable").copy(prerequisite = AppAuditPrerequisite.NOT_CONFIGURED)
        val result = appAuditPresentation(auditRun(listOf(configured, unavailable), listOf(auditResult(configured, source = AppAuditObservationSource.CONFIGURATION), auditResult(unavailable, AppAuditOutcome.UNAVAILABLE, attempted = false))))
        assertNull(result.score.score)
        assertEquals("Measured check pass score: Not measured", result.scoreText)
        assertEquals("Exercised 0/2 planned checks · Verified passes 0/2 planned checks", result.coverageText)
        assertEquals(1, result.summary.unavailable)
    }

    @Test fun pendingAndExternalChecksRemainDistinguishableFromFailures() {
        val pending = auditEntry("pending")
        val external = auditEntry("screen-reader", AppAuditEvidenceKind.EXTERNAL_SUITE, "ui").copy(prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED)
        val result = appAuditPresentation(auditRun(listOf(pending, external), listOf(auditResult(external, AppAuditOutcome.UNTESTED, attempted = false))))
        assertEquals(2, result.summary.untested)
        assertEquals(0, result.summary.failed)
        val entries = result.components.flatMap { it.entries }
        assertEquals("Not yet tested", appAuditOutcomeLabel(entries.single { it.entry.id == pending.id }.result?.outcome))
        assertTrue(appAuditReason(entries.single { it.entry.id == external.id }).contains("external test harness"))
    }

    @Test fun unknownReasonCodeIsNotRenderedAsProviderOrImportedCopy() {
        val entry = auditEntry("provider")
        val privateCode = "PRIVATE_ENDPOINT_OR_SERVER_TEXT"
        val result = appAuditPresentation(auditRun(listOf(entry), listOf(auditResult(entry, AppAuditOutcome.FAIL, reason = privateCode))))
        val reason = appAuditReason(result.components.single().entries.single())
        assertEquals("The named assertion failed. Inspect its recorded evidence.", reason)
        assertFalse(reason.contains(privateCode))
    }

    @Test fun disabledNetworkChecksGiveActionableGuidanceWithoutClaimingServiceFailure() {
        val entry = auditEntry("network", AppAuditEvidenceKind.LIVE_METADATA).copy(selected = false, reasonCode = "NETWORK_READS_DISABLED")
        val item = appAuditPresentation(auditRun(listOf(entry))).components.single().entries.single()
        assertEquals("Service reads were disabled for this plan. Enable them and start a new audit to measure this connection.", appAuditReason(item))
        assertFalse(item.includedInScore)
    }

    @Test fun serviceInterruptionExplainsRecoveryWithoutErasingEvidenceOrMeasuringPendingChecks() {
        val passed = auditEntry("passed")
        val interrupted = auditEntry("interrupted")
        val pending = auditEntry("pending")
        val passedResult = auditResult(passed)
        val interruptedResult = auditResult(interrupted, AppAuditOutcome.CANCELED, AppAuditObservationSource.NONE, reason = "SERVICE_INTERRUPTION").let {
            it.copy(observation = it.observation.copy(dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN), durationMs = null)
        }
        val run = auditRun(listOf(passed, interrupted, pending), listOf(passedResult, interruptedResult)).copy(stopReasonCode = "SERVICE_INTERRUPTION")
        val presentation = appAuditPresentation(run)
        val item = presentation.components.single().entries.single { it.entry.id == interrupted.id }
        val explanation = "Android background tracking was interrupted. Recorded evidence is retained; only remaining checks can resume."
        assertEquals(explanation, appAuditReasonCodeMessage(run.stopReasonCode))
        assertEquals(explanation, appAuditReason(item))
        assertEquals(listOf(pending), run.pendingEntries)
        assertEquals(listOf(passedResult, interruptedResult), run.results)
        assertEquals("Exercised 1/3 planned checks · Verified passes 1/3 planned checks", presentation.coverageText)
        assertEquals(1, presentation.score.measuredWeight)
        assertEquals(1, presentation.summary.canceled)
        assertEquals(1, presentation.summary.untested)
        assertFalse(item.includedInScore)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, item.result?.dispatchEvidence)
        assertNull(item.result?.durationMs)
    }

    @Test fun foreignAndDuplicateResultsCannotReplaceTheFrozenPlanOrItsFirstObservation() {
        val accepted = auditEntry("accepted")
        val foreign = auditEntry("foreign")
        val run = auditRun(listOf(accepted), listOf(auditResult(accepted, AppAuditOutcome.FAIL), auditResult(accepted), auditResult(foreign)))
        val presentation = appAuditPresentation(run)
        assertEquals(1, presentation.summary.planned)
        assertEquals(0.0, presentation.score.score!!, 0.0)
        assertEquals(AppAuditOutcome.FAIL, presentation.components.single().entries.single().result?.outcome)
        assertEquals(1, presentation.components.sumOf { it.entries.size })
    }

    @Test fun renamedOrMissingCurrentConnectionNeverChangesItsFrozenAliasOrEvidence() {
        val first = auditEntry("first").copy(targetId = "connection:one", configFingerprint = "accepted-fingerprint")
        val second = auditEntry("second").copy(targetId = "connection:two")
        val run = auditRun(listOf(first, second), listOf(auditResult(first)))
        fun rows(names: Map<String, String>) = appAuditPresentation(run, names).components.single().entries
        val original = rows(appAuditCurrentTargetNames(emptyList(), listOf("one" to "Original saved name", "two" to "Other connection")))
        val renamed = rows(appAuditCurrentTargetNames(emptyList(), listOf("one" to "Renamed saved name", "two" to "Other connection")))
        val removed = rows(appAuditCurrentTargetNames(emptyList(), listOf("two" to "Original saved name")))
        assertEquals("target-1", original.first().targetAlias)
        assertEquals(original.first().targetAlias, renamed.first().targetAlias)
        assertEquals(original.first().targetAlias, removed.first().targetAlias)
        assertEquals("Renamed saved name", renamed.first().currentSavedName)
        assertNull(removed.first().currentSavedName)
        assertEquals("target-2", removed.last().targetAlias)
        assertEquals("Original saved name", removed.last().currentSavedName)
        assertEquals("accepted-fingerprint", run.plan.first().configFingerprint)
        assertEquals(auditResult(first), removed.first().result)
        assertFalse(run.toString().contains("Renamed saved name"))
    }

    @Test fun currentNamesUseExactTypedIdentityAndRejectAmbiguousUidMatches() {
        val names = appAuditCurrentTargetNames(
            listOf("same-id" to "Gateway name", "ambiguous" to "First", "ambiguous" to "Second"),
            listOf("same-id" to "Connection name\n")
        )
        assertEquals("Gateway name", names["gateway:same-id"])
        assertEquals("Connection name", names["connection:same-id"])
        assertNull(names["gateway:ambiguous"])
        assertNull(names["connection:missing"])
    }
}
