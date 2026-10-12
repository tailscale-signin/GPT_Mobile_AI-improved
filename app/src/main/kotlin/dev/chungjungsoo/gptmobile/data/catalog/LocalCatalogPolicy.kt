package dev.chungjungsoo.gptmobile.data.catalog

/** A listing is not a runtime qualification. Native admission remains authoritative. */
data class CatalogSnapshot(
    val entries: List<CatalogEntry>,
    val revision: Int = 0,
    val source: String = "Bundled / cached catalog",
    val refreshError: String? = null,
    val complete: Boolean = true,
    val upstream: CatalogSource? = null
)

enum class CatalogAction { DOWNLOAD, REVIEW_ACCESS, VIEW_BUILD, VIEW_REASON, VERIFY, USE }
data class CatalogSupportDecision(val action: CatalogAction, val reasons: List<String>, val canDownload: Boolean, val canUse: Boolean)

object LocalCatalogCompatibilityResolver {
    fun resolve(entry: CatalogEntry, geniexEnabled: Boolean, soc: String, installed: Boolean = false): CatalogSupportDecision {
        val geniex = entry.variantLabel.startsWith("GENIEX_") || entry.runtimeRequirement.contains("geniex", true) || entry.downloadUrl.substringBefore('?').endsWith(".gguf", true)
        val reasons = buildList {
            if (geniex && !geniexEnabled) add("Requires the separate GenieX build")
            if (entry.coverageStatus != "represented") add(entry.coverageStatus.replace('_', ' '))
            if (entry.sourceChipset.isNotBlank() && !entry.sourceChipset.equals(soc, true)) add("This variant targets ${entry.sourceChipset}; exact chipset matching is required")
            if (ModelCatalogParser.compareAppVersions(entry.minAppVersion, dev.chungjungsoo.gptmobile.BuildConfig.VERSION_NAME) > 0) add("Requires app ${entry.minAppVersion}")
            if (!installed && entry.downloadUrl.isBlank()) add("Artifact metadata is unresolved")
            if (entry.supportedAccelerators.isEmpty()) add("Processor compatibility has not been declared")
            val npuOnly = entry.supportedAccelerators.all { it.equals("npu", true) }
            if (npuOnly && !geniex && !dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators.isNpuEligible(entry.supportedAccelerators, entry.socToModelFiles, soc)) add("No exact NPU package for this chipset")
        }
        val action = when {
            geniex && !geniexEnabled -> CatalogAction.VIEW_BUILD
            reasons.isNotEmpty() -> CatalogAction.VIEW_REASON
            installed -> CatalogAction.VERIFY
            entry.isGated -> CatalogAction.REVIEW_ACCESS
            else -> CatalogAction.DOWNLOAD
        }
        return CatalogSupportDecision(action, reasons, reasons.isEmpty() && !installed, false)
    }
}

object LocalCatalogRecommendationPolicy {
    private val curated = listOf("gemma-3-1b", "qwen2.5-1.5b", "deepseek", "gemma-3n", "gemma-4", "gemma-3-270m", "qwen3")
    fun recommend(entries: List<CatalogEntry>, geniexEnabled: Boolean, soc: String, ramGb: Long? = null): List<CatalogEntry> = entries
        .filter { LocalCatalogCompatibilityResolver.resolve(it, geniexEnabled, soc).canDownload && (ramGb == null || it.minRamGb <= ramGb) }
        .sortedWith(compareBy<CatalogEntry> { curated.indexOfFirst { family -> (it.familyId + it.id).contains(family, true) }.takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { if (it.sizeInBytes > 0) it.sizeInBytes else Long.MAX_VALUE }.thenBy { it.id })
        .distinctBy { it.familyId.ifBlank { it.id } }.take(6)
}
