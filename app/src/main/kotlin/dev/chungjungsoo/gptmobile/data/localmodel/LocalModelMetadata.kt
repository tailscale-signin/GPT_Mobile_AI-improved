package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.Context
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import java.io.File
import kotlinx.serialization.json.Json

/** Store capabilities alongside each package so Hub downloads still support tools after restarting. */
internal object LocalModelMetadata {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private const val FILE_NAME = ".catalog.json"

    fun save(directory: File, entry: CatalogEntry) {
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, FILE_NAME).writeText(json.encodeToString(entry))
    }

    fun readFromDirectory(directory: File): CatalogEntry? {
        val metadata = File(directory, FILE_NAME)
        if (!metadata.isFile || metadata.length() > 256 * 1024) return null
        return runCatching {
            val entry = json.decodeFromString<CatalogEntry>(metadata.readText())
            if (entry.variantLabel == ArtifactRuntime.LLAMA_CPP.name && entry.runtimeRequirement.startsWith("llama_cpp:")) {
                withGgufBackends(entry, dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.gguf(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()).backends)
            } else {
                entry
            }
        }.getOrNull()
    }

    /** Older imports recorded CPU-only engine support; GGUF tensors are portable. */
    internal fun withGgufBackends(entry: CatalogEntry, backends: Set<String>): CatalogEntry {
        if (entry.variantLabel != ArtifactRuntime.LLAMA_CPP.name || !entry.runtimeRequirement.startsWith("llama_cpp:") || "cpu" !in entry.supportedAccelerators) return entry
        return entry.copy(
            displayName = if (entry.displayName.endsWith(" · GenieX Preview")) entry.displayName.removeSuffix(" · GenieX Preview") + " · GGUF" else entry.displayName,
            supportedAccelerators = backends.sorted()
        )
    }

    fun read(context: Context): List<CatalogEntry> {
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).distinctBy { it.canonicalPath }
        return roots.flatMap { File(it, "models").listFiles().orEmpty().toList() }.filter { it.isDirectory }.flatMap { model ->
            model.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { revision ->
                readFromDirectory(revision)?.takeIf { it.id == model.name }?.let { entry ->
                    val stored = revision.listFiles().orEmpty()
                        .singleOrNull { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
                    // Repair legacy catalog metadata in memory without copying weights.
                    if (stored != null) LocalModelPackages.forInstalledFile(entry, stored.name) else entry
                }
            }
        }
    }
}
