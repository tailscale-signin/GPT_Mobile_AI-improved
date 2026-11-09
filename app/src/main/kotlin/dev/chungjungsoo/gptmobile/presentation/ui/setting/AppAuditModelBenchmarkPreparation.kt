package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSafetyClass
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelTarget
import dev.chungjungsoo.gptmobile.data.audit.appAuditTargetDigest

/** Current credential/configuration revision must match before an archived check can select a model. */
internal suspend fun appAuditBenchmarkProfileUid(
    entry: AppAuditPlanEntry,
    target: suspend (String) -> AppAuditSelectedModelTarget?
): String? {
    val uid = appAuditModelProfileUid(entry) ?: return null
    if (!entry.selected || entry.safetyClass != AppAuditSafetyClass.EXTERNAL_ONLY) return null
    val revision = entry.configurationRevision ?: return null
    val current = target(uid) ?: return null
    return uid.takeIf {
        current.key == entry.targetId &&
            current.configured &&
            current.enabled &&
            current.configurationRevision == revision &&
            current.fingerprint == entry.configFingerprint &&
            entry.id == "${AppAuditSelectedModelProbeSource.SOURCE_ID}:${AppAuditSelectedModelProbeSource.GENERATION}:${appAuditTargetDigest(current.key)}"
    }
}
