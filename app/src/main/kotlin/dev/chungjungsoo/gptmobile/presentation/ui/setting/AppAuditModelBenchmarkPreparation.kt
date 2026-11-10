package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditModelBenchmarkBinding
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSafetyClass
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelTarget
import dev.chungjungsoo.gptmobile.data.audit.appAuditModelBenchmarkBinding
import dev.chungjungsoo.gptmobile.data.audit.appAuditTargetDigest
import dev.chungjungsoo.gptmobile.data.audit.matches
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

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

/** The originating run and full frozen profile survive the screen-to-queue handoff. */
internal suspend fun appAuditPrepareModelBenchmark(
    run: AppAuditRun,
    entry: AppAuditPlanEntry,
    profile: PlatformV2,
    target: suspend (String) -> AppAuditSelectedModelTarget?
): AppAuditModelBenchmarkBinding? {
    val binding = appAuditModelBenchmarkBinding(run, entry, profile) ?: return null
    val current = target(profile.uid)
    if (appAuditBenchmarkProfileUid(entry) { current } != profile.uid) return null
    return binding.takeIf { it.matches(current) }
}
