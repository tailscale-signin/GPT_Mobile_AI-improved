package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.Serializable

/** A retained selection for a separate benchmark, never an imported audit pass or execution grant. */
@Serializable
data class AppAuditModelBenchmarkBinding(
    val auditRunId: String,
    val auditEntryId: String,
    val runBinding: String,
    val qualificationBinding: String,
    val profileUid: String,
    val profileSnapshotHash: String,
    val configurationRevision: String
)

/** Per-request observations. A terminal provider event alone does not prove configuration stability. */
@Serializable
data class AppAuditModelConfigurationReceipt(
    val configurationRevision: String,
    val admittedRounds: Int,
    val completedRounds: Int,
    val beforeGenerationVerified: Boolean,
    val afterGenerationVerified: Boolean,
    val observedAtEpochMs: Long,
    val failureCode: String? = null
)

internal fun appAuditBenchmarkProfileHash(profile: PlatformV2): String = appAuditHash(profile.copy(token = null).toString())

internal fun appAuditModelBenchmarkBinding(run: AppAuditRun, entry: AppAuditPlanEntry, profile: PlatformV2): AppAuditModelBenchmarkBinding? {
    if (run.catalogHash != appAuditPlanHash(run.plan, run.options, run.sourceInventoryDigest, run.limits) ||
        run.plan.singleOrNull { it.id == entry.id } != entry ||
        !appAuditModelGenerationEntry(entry, profile.uid)
    ) {
        return null
    }
    val configuration = run.plan.singleOrNull {
        it.sourceId == AppAuditSelectedModelProbeSource.SOURCE_ID && it.probeId == AppAuditSelectedModelProbeSource.CONFIGURATION && it.targetId == entry.targetId
    } ?: return null
    val result = run.results.singleOrNull { it.entryId == configuration.id } ?: return null
    if (result.outcome != AppAuditOutcome.PASS ||
        result.observation.configChanged ||
        result.observation.configurationRevision != entry.configurationRevision ||
        configuration.configurationRevision != entry.configurationRevision ||
        configuration.configFingerprint != entry.configFingerprint
    ) {
        return null
    }
    val revision = safeAuditConfigurationRevision(entry.configurationRevision) ?: return null
    return AppAuditModelBenchmarkBinding(
        run.id,
        entry.id,
        appAuditQualificationRunBinding(run) ?: return null,
        appAuditQualificationCheckBinding(run, entry) ?: return null,
        profile.uid,
        appAuditBenchmarkProfileHash(profile),
        revision
    )
}

internal fun appAuditModelGenerationEntry(entry: AppAuditPlanEntry, profileUid: String): Boolean =
    profileUid.isNotBlank() &&
        entry.sourceId == AppAuditSelectedModelProbeSource.SOURCE_ID &&
        entry.probeId == AppAuditSelectedModelProbeSource.GENERATION &&
        entry.evidenceKind == AppAuditEvidenceKind.MODEL_EXECUTION &&
        entry.selected &&
        entry.safetyClass == AppAuditSafetyClass.EXTERNAL_ONLY &&
        entry.targetId == AppAuditSelectedModelProbeSource.TARGET_PREFIX + profileUid &&
        entry.id == "${AppAuditSelectedModelProbeSource.SOURCE_ID}:${AppAuditSelectedModelProbeSource.GENERATION}:${appAuditTargetDigest(AppAuditSelectedModelProbeSource.TARGET_PREFIX + profileUid)}"

internal fun AppAuditModelBenchmarkBinding.matches(run: AppAuditRun?, profile: PlatformV2): Boolean {
    val accepted = run?.takeIf { it.id == auditRunId } ?: return false
    val entry = accepted.plan.singleOrNull { it.id == auditEntryId } ?: return false
    return appAuditModelBenchmarkBinding(accepted, entry, profile) == this
}

internal fun AppAuditModelBenchmarkBinding.matches(current: AppAuditSelectedModelTarget?): Boolean = current != null &&
    current.key == AppAuditSelectedModelProbeSource.TARGET_PREFIX + profileUid &&
    current.configured &&
    current.enabled &&
    current.configurationRevision == configurationRevision &&
    current.profileSnapshotHash == profileSnapshotHash

internal fun appAuditModelBenchmarkIssue(
    binding: AppAuditModelBenchmarkBinding,
    profile: PlatformV2,
    run: AppAuditRun?,
    current: AppAuditSelectedModelTarget?,
    sourceCommit: String,
    buildVersion: String,
    buildVariant: String,
    sourceInventoryDigest: String
): String? = when {
    run == null || !binding.matches(run, profile) -> "AUDIT_MODEL_BINDING_UNAVAILABLE"
    run.sourceCommit != sourceCommit || run.buildVersion != buildVersion || run.buildVariant != buildVariant || run.sourceInventoryDigest != sourceInventoryDigest -> "AUDIT_MODEL_BUILD_CHANGED"
    !binding.matches(current) || current?.fingerprint != run.plan.single { it.id == binding.auditEntryId }.configFingerprint -> "AUDIT_MODEL_CONFIGURATION_CHANGED"
    else -> null
}

internal class AppAuditModelQualificationException(val code: String, val beforeDispatch: Boolean = true) :
    IllegalStateException(
        when (code) {
            "AUDIT_MODEL_CONFIGURATION_CHANGED" -> "The audit's selected model configuration or credentials changed. Start a fresh audit before preparing this benchmark again."
            "AUDIT_MODEL_BUILD_CHANGED" -> "The saved audit belongs to a different app build. Start a fresh audit before preparing its model benchmark."
            else -> "The saved audit model binding is unavailable or no longer matches this benchmark selection. Prepare the intended audit check again."
        }
    )
