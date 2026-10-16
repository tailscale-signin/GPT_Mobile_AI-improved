package dev.chungjungsoo.gptmobile.data.localmodel

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.catalog.SocVariant
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.localruntime.QualcommSocSupport
import java.io.File

/** A compiled NPU package cannot be opened by the GPU/CPU backend. */
object LocalModelPackages {
    fun forDevice(entries: List<CatalogEntry>, soc: String): List<CatalogEntry> = entries.flatMap { entry ->
        val generic = entry.supportedAccelerators.filter { it.equals("cpu", true) || it.equals("gpu", true) }
        val eligible = LocalAccelerators.isNpuEligible(entry.supportedAccelerators, entry.socToModelFiles, soc)
        when {
            eligible -> buildList {
                // Keep the original ID for existing device-specific downloads and profiles.
                val resolved = SocVariantResolver.resolve(entry, soc)
                add(
                    entry.copy(
                        displayName = "${entry.displayName} · NPU",
                        downloadUrl = resolved.downloadUrl,
                        sizeInBytes = resolved.sizeInBytes,
                        sha256 = resolved.sha256,
                        supportedAccelerators = listOf("npu")
                    )
                )
                if (generic.isNotEmpty()) {
                    add(
                        entry.copy(
                            id = "${entry.id}-litert",
                            displayName = "${entry.displayName} · GPU / CPU",
                            supportedAccelerators = generic,
                            socToModelFiles = emptyMap()
                        )
                    )
                }
            }
            generic.isNotEmpty() -> listOf(entry.copy(supportedAccelerators = generic, socToModelFiles = emptyMap()))
            else -> emptyList()
        }
    }

    // A catalogue ID or parent directory can contain "qualcomm" even when the
    // installed file is its GPU edition. Only the artifact name identifies it.
    fun npuSoc(fileName: String): String? = Regex("(?i)(?:^|[^a-z0-9])(sm8[0-9]{3})(?:[^0-9]|$)")
        .find(fileName.substringAfterLast('/'))?.groupValues?.get(1)?.uppercase()

    fun isNpuFile(fileName: String): Boolean = npuSoc(fileName) != null ||
        Regex("(?i)(?:npu|qualcomm|mediatek|tensor[_-]?g[0-9])").containsMatchIn(fileName.substringAfterLast('/'))

    /**
     * An imported artifact has no remote catalog row. Its original name is
     * candidate backend/SoC metadata, not a verified NPU execution result.
     */
    fun forImportedLiteRtModel(
        id: String,
        originalFileName: String,
        installedFileName: String,
        sizeInBytes: Long
    ): CatalogEntry = forInstalledFile(
        CatalogEntry(
            id = id,
            displayName = originalFileName.substringBeforeLast('.'),
            sizeInBytes = sizeInBytes,
            familyId = id,
            variantLabel = "LITERT_LM",
            runtimeRequirement = "litert-lm"
        ),
        installedFileName
    )

    /**
     * Pre-fix imports used a generic model.litertlm name. Recover the original
     * identity only from app-owned metadata in the matching hash-ID directory.
     */
    fun isCompiledNpuPackage(modelPath: String): Boolean {
        if (isNpuFile(modelPath)) return true
        val file = File(modelPath)
        if (!file.isFile || !file.name.equals("model.litertlm", ignoreCase = true)) return false
        val revision = file.parentFile ?: return false
        val installedEntry = LocalModelMetadata.readFromDirectory(revision) ?: return false
        return installedEntry.id.startsWith("local_") &&
            revision.parentFile?.name == installedEntry.id &&
            installedEntry.variantLabel.equals("LITERT_LM", ignoreCase = true) &&
            npuSoc(installedEntry.displayName) != null
    }

    /**
     * Accept an installed Qualcomm package when either the current catalog proves
     * an exact SoC variant or the artifact basename itself encodes this phone's
     * supported Snapdragon SoC. Dynamic Hub entries are intentionally not assumed
     * to remain in the in-memory catalog after download/app restart.
     *
     * Parent directory names never count as package evidence: an exact SoC token
     * must be present in the artifact basename so generic/GPU files cannot be
     * promoted to QNN accidentally.
     */
    fun isNpuPackageCompatible(
        entry: CatalogEntry?,
        installedFileName: String?,
        deviceSocModel: String
    ): Boolean {
        if (QualcommSocSupport.htpVersion(deviceSocModel) == null) return false
        if (entry != null &&
            LocalAccelerators.isNpuEligible(entry.supportedAccelerators, entry.socToModelFiles, deviceSocModel)
        ) {
            return true
        }
        val packageSoc = installedFileName?.takeIf(String::isNotBlank)?.let(::npuSoc) ?: return false
        return QualcommSocSupport.canonicalSoc(packageSoc) == QualcommSocSupport.canonicalSoc(deviceSocModel)
    }

    fun forInstalledFile(entry: CatalogEntry, fileName: String): CatalogEntry {
        if (fileName == "manifest.json" && entry.variantLabel.startsWith("GENIEX_")) return entry
        // Legacy manual imports retained the original basename in the manifest.
        // Do not infer vendor support from parent directories or catalog titles.
        val artifactName = if (
            fileName.equals("model.litertlm", ignoreCase = true) &&
            entry.id.startsWith("local_") &&
            entry.variantLabel.equals("LITERT_LM", ignoreCase = true) &&
            npuSoc(entry.displayName) != null
        ) {
            entry.displayName + ".litertlm"
        } else {
            fileName
        }
        if (!isNpuFile(artifactName)) {
            return entry.copy(
                supportedAccelerators = entry.supportedAccelerators.filterNot { it.equals("npu", true) }.ifEmpty { listOf("gpu", "cpu") },
                socToModelFiles = emptyMap()
            )
        }
        val soc = npuSoc(artifactName)
        return entry.copy(
            supportedAccelerators = listOf("npu"),
            socToModelFiles = entry.socToModelFiles.filterKeys {
                soc == null || QualcommSocSupport.canonicalSoc(it).equals(soc, true)
            }.ifEmpty {
                if (soc != null && QualcommSocSupport.htpVersion(soc) != null) {
                    mapOf(soc to SocVariant(modelFile = fileName))
                } else {
                    emptyMap()
                }
            }
        )
    }
}
