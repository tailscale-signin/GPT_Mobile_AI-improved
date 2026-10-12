package dev.chungjungsoo.gptmobile.data.catalog

import kotlinx.serialization.Serializable

@Serializable
data class ModelCatalog(
    val schemaVersion: Int = 0,
    val revision: Int = 0,
    val models: List<CatalogEntry> = emptyList(),
    val source: CatalogSource? = null,
    val coverage: List<CatalogCoverage> = emptyList()
)

@Serializable
data class CatalogSource(val upstreamCommit: String, val retrievedAt: String, val inputDigest: String, val familyCount: Int, val artifactCount: Int, val complete: Boolean)

@Serializable
data class CatalogCoverage(val familyId: String, val artifactId: String, val status: String, val runtime: String, val chipset: String, val precision: String = "")

@Serializable
data class CatalogEntry(
    val id: String = "",
    val displayName: String = "",
    val downloadUrl: String = "",
    val sizeInBytes: Long = 0L,
    val minRamGb: Int = 0,
    val isGated: Boolean = false,
    val capabilities: CatalogCapabilities = CatalogCapabilities(),
    val supportedAccelerators: List<String> = emptyList(),
    val defaultConfig: CatalogDefaultConfig = CatalogDefaultConfig(),
    val minAppVersion: String = "0.0.0",
    val maxContextTokens: Int = 0,
    val socToModelFiles: Map<String, SocVariant> = emptyMap(),
    val supportsMtp: Boolean = false,
    val manifestVersion: Int = 1,
    val sha256: String = "",
    val upstreamRevision: String = "",
    val tokenizerFormat: String = "",
    val runtimeRequirement: String = "",
    val familyId: String = "",
    val variantLabel: String = "",
    val precision: String = "",
    val coverageStatus: String = "represented",
    val sourceChipset: String = "",
    val sourceUrl: String = "",
    val licenseUrl: String = "",
    val redistributionRestricted: Boolean = false,
    val discoveryAssets: List<CatalogDiscoveryAsset> = emptyList()
)

@Serializable
data class CatalogDiscoveryAsset(val path: String, val sizeInBytes: Long = 0, val sha256: String = "", val role: String = "model")

@Serializable
data class CatalogCapabilities(
    val vision: Boolean = false,
    val npuVision: Boolean = false,
    val tools: Boolean = false,
    val thinking: Boolean = false
)

@Serializable
data class CatalogDefaultConfig(
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 1024
)

@Serializable
data class SocVariant(
    val modelFile: String = "",
    val downloadUrl: String = "",
    val commitHash: String = "",
    val sizeInBytes: Long = 0L,
    val contextSize: Int = 0,
    val quantization: String = "",
    val sha256: String = "",
    val runtimeRequirement: String = ""
)

/** MTP is multi-token prediction, not the MediaTek accelerator. */
fun CatalogEntry.hasMtp(): Boolean = supportsMtp
