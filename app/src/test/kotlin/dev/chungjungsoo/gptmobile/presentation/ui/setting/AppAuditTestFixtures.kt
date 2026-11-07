package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservation
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditResult
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun

internal fun auditEntry(id: String, kind: AppAuditEvidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT, component: String = "tools", weight: Int = 1) = AppAuditPlanEntry(
    id = id,
    sourceId = "test-source",
    probeId = id,
    componentId = component,
    label = "Check $id",
    sourceAreaIds = listOf(component),
    evidenceKind = kind,
    weight = weight
)

internal fun auditResult(
    entry: AppAuditPlanEntry,
    outcome: AppAuditOutcome = AppAuditOutcome.PASS,
    source: AppAuditObservationSource = AppAuditObservationSource.FIXTURE,
    attempted: Boolean = true,
    reason: String = "CHECK_PASSED"
) = AppAuditResult(
    entry.id,
    AppAuditObservation(outcome, reason, evidenceKind = entry.evidenceKind, observationSource = source),
    startedAtEpochMs = 1000,
    finishedAtEpochMs = 1010,
    durationMs = 10,
    attempted = attempted
)

internal fun auditRun(plan: List<AppAuditPlanEntry>, results: List<AppAuditResult> = emptyList()) = AppAuditRun(
    id = "audit-1",
    startedAtEpochMs = 1000,
    catalogHash = "catalog-hash",
    sourceCommit = "0123456789abcdef",
    buildVersion = "test-build",
    buildVariant = "debug",
    sourceInventoryDigest = "inventory-hash",
    sourceFileCount = 20,
    sourceLineCount = 200,
    plan = plan,
    results = results
)
