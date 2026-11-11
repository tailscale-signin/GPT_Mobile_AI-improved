package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

internal data class AppAuditModelFixture(
    val profile: PlatformV2,
    val target: AppAuditSelectedModelTarget,
    val run: AppAuditRun,
    val entry: AppAuditPlanEntry,
    val binding: AppAuditModelBenchmarkBinding
)

internal suspend fun appAuditModelFixture(): AppAuditModelFixture {
    val profile = PlatformV2(uid = "audit-model", name = "Selected model", model = "accepted-model", enabled = true)
    val target = AppAuditSelectedModelTarget("model:${profile.uid}", true, true, "accepted-profile-and-credential", "a".repeat(64), profileSnapshotHash = appAuditBenchmarkProfileHash(profile))
    val options = AppAuditOptions(selectedProfileIds = listOf(profile.uid))
    val entries = AppAuditSelectedModelProbeSource({ listOf(target) }, { target }).plan(options)
    val configuration = entries.single { it.probeId == AppAuditSelectedModelProbeSource.CONFIGURATION }
    val entry = entries.single { it.evidenceKind == AppAuditEvidenceKind.MODEL_EXECUTION }
    val limits = AppAuditLimits()
    val run = AppAuditRun(
        "source-audit", 1, appAuditPlanHash(entries, options, AppAuditSourceInventory.sourceDigest, limits),
        BuildConfig.APP_COMMIT, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE, AppAuditSourceInventory.sourceDigest,
        AppAuditSourceInventory.fileCount, AppAuditSourceInventory.lineCount, entries,
        sourceAreas = AppAuditSourceInventory.areas, options = options, limits = limits,
        results = listOf(
            AppAuditResult(configuration.id, AppAuditObservation(AppAuditOutcome.PASS, "MODEL_CONFIGURATION_ONLY", observationSource = AppAuditObservationSource.CONFIGURATION, configurationRevision = target.configurationRevision), 1, 2, 1, true),
            AppAuditResult(entry.id, AppAuditObservation(AppAuditOutcome.UNTESTED, "MODEL_BENCHMARK_REQUIRED", evidenceKind = AppAuditEvidenceKind.MODEL_EXECUTION), 2, 3, 1)
        ),
        finishedAtEpochMs = 3
    )
    return AppAuditModelFixture(profile, target, run, entry, checkNotNull(appAuditModelBenchmarkBinding(run, entry, profile)))
}
