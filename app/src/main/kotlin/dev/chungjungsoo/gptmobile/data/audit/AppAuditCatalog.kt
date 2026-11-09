package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Freezes the accepted scope before any live probe can run. Planning reads configuration only. */
@Singleton
class AppAuditCatalog @Inject constructor(sources: Set<@JvmSuppressWildcards AppAuditProbeSource>) {
    private val sourcesById = sources.associateBy { it.sourceId }.also { require(it.size == sources.size) }

    suspend fun create(
        options: AppAuditOptions = AppAuditOptions(),
        limits: AppAuditLimits = AppAuditLimits(),
        id: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis()
    ): AppAuditRun {
        require(options.selectedProfileIds.size <= 128)
        val frozenOptions = options.copy(selectedProfileIds = options.selectedProfileIds.distinct().sorted())
        val proposed = sourcesById.toSortedMap().flatMap { (sourceId, source) ->
            try {
                withTimeout(5_000) { source.plan(frozenOptions) }.also { entries ->
                    require(entries.size <= 2_048 && entries.all { it.sourceId == sourceId })
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                listOf(
                    AppAuditPlanEntry(
                        id = "$sourceId.inventory-unavailable",
                        sourceId = sourceId,
                        probeId = "inventory-unavailable",
                        componentId = sourceId,
                        label = "Component inventory unavailable",
                        prerequisite = AppAuditPrerequisite.UNSUPPORTED,
                        reasonCode = "INVENTORY_UNAVAILABLE"
                    )
                )
            }
        }
        require(proposed.map { it.id }.distinct().size == proposed.size)
        require(proposed.size <= 4_096)
        // A narrow configuration/contract check does not replace the area's
        // execution suite (notably Compose rendering and database recovery).
        val suitesPlanned = proposed.filter { it.evidenceKind == AppAuditEvidenceKind.EXTERNAL_SUITE }.flatMap { it.sourceAreaIds }.toSet()
        val inventoryEntries = AppAuditSourceInventory.areas.filter { it.id !in suitesPlanned }.map { area ->
            AppAuditPlanEntry(
                id = "source-area:${area.id}",
                sourceId = "source-inventory",
                probeId = "external-coverage",
                componentId = area.id,
                label = area.label,
                sourceAreaIds = listOf(area.id),
                evidenceKind = AppAuditEvidenceKind.EXTERNAL_SUITE,
                safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY,
                prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED,
                reasonCode = "EXTERNAL_HARNESS_REQUIRED"
            )
        }
        val deviceMeasurement = AppAuditPlanEntry(
            id = "source-inventory:device-performance",
            sourceId = "source-inventory",
            probeId = "device-performance",
            componentId = "android_shell",
            label = "Device rendering and resource measurements",
            sourceAreaIds = listOf("android_shell", "chat_ui", "local_inference", "native_bridge"),
            evidenceKind = AppAuditEvidenceKind.DEVICE_MEASUREMENT,
            safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY,
            prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED,
            reasonCode = "DEVICE_MEASUREMENT_REQUIRED"
        )
        val plan = proposed + inventoryEntries + deviceMeasurement
        require(plan.size <= 4_096 && proposed.flatMap { it.sourceAreaIds }.all { id -> AppAuditSourceInventory.areas.any { it.id == id } })
        return AppAuditRun(
            id = id,
            startedAtEpochMs = now,
            catalogHash = appAuditPlanHash(plan, frozenOptions, AppAuditSourceInventory.sourceDigest, limits),
            sourceCommit = BuildConfig.APP_COMMIT,
            buildVersion = BuildConfig.VERSION_NAME,
            buildVariant = BuildConfig.BUILD_TYPE,
            sourceInventoryDigest = AppAuditSourceInventory.sourceDigest,
            sourceFileCount = AppAuditSourceInventory.fileCount,
            sourceLineCount = AppAuditSourceInventory.lineCount,
            plan = plan,
            sourceAreas = AppAuditSourceInventory.areas,
            options = frozenOptions,
            limits = limits
        )
    }

    internal fun source(id: String): AppAuditProbeSource? = sourcesById[id]

    internal fun incompatibility(run: AppAuditRun): String? = when {
        run.schemaVersion != 1 || run.catalogVersion != "app-audit-v1" -> "AUDIT_VERSION_CHANGED"
        run.plan.size > 4_096 || run.sourceAreas.size > 128 || run.results.size > run.plan.size -> "INVALID_AUDIT_MANIFEST"
        run.lineCoverageAvailable -> "INVALID_COVERAGE_CLAIM"
        run.sourceCommit != BuildConfig.APP_COMMIT || run.sourceInventoryDigest != AppAuditSourceInventory.sourceDigest || run.buildVersion != BuildConfig.VERSION_NAME || run.buildVariant != BuildConfig.BUILD_TYPE -> "AUDIT_SOURCE_CHANGED"
        run.sourceAreas != AppAuditSourceInventory.areas || run.sourceFileCount != AppAuditSourceInventory.fileCount || run.sourceLineCount != AppAuditSourceInventory.lineCount -> "AUDIT_MANIFEST_CHANGED"
        run.catalogHash != appAuditPlanHash(run.plan, run.options, run.sourceInventoryDigest, run.limits) -> "AUDIT_MANIFEST_CHANGED"
        run.plan.map { it.id }.distinct().size != run.plan.size -> "INVALID_AUDIT_MANIFEST"
        run.results.map { it.entryId }.distinct().size != run.results.size || run.results.any { result -> run.plan.none { it.id == result.entryId } } -> "INVALID_AUDIT_RECEIPTS"
        run.activeProbeId != null && run.plan.none { it.id == run.activeProbeId } -> "INVALID_AUDIT_RECEIPTS"
        run.executedProbes < run.results.count { it.attempted } || run.networkProbesDispatched !in 0..run.limits.maxNetworkProbes || run.networkProbesAttempted !in 0..run.limits.maxNetworkProbes -> "INVALID_AUDIT_BUDGET"
        else -> null
    }
}

internal fun appAuditPlanHash(plan: List<AppAuditPlanEntry>, options: AppAuditOptions, sourceDigest: String, limits: AppAuditLimits): String = appAuditHash(
    "app-audit-v1\n$sourceDigest\n${Json.encodeToString(options)}\n${Json.encodeToString(limits)}\n${Json.encodeToString(plan)}"
)

internal fun appAuditHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
