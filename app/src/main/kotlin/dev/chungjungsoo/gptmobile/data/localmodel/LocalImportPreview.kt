package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

data class LocalImportPreview(
    val name: String,
    val runtime: String,
    val sourceBytes: Long?,
    val installedBytes: Long?,
    val backends: List<String> = emptyList(),
    val contextTokens: Int? = null,
    val assets: Int = 1
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Header/manifest inspection only. No native model is loaded and no weights are copied. */
        fun inspect(input: InputStream, name: String, size: Long?, geniex: Boolean, folder: Boolean = false): LocalImportPreview = input.buffered().use { stream ->
            if (folder) return@use manifest(stream, name, null, geniex)
            stream.mark(16)
            val header = ByteArray(8)
            var count = 0
            while (count < header.size) {
                val read = stream.read(header, count, header.size - count)
                if (read < 0) break
                count += read
            }
            stream.reset()
            when {
                header.contentEquals("LITERTLM".toByteArray()) -> {
                    require(name.endsWith(".litertlm", true)) { "This LiteRT-LM container needs the .litertlm filename extension." }
                    require(size == null || size >= dev.chungjungsoo.gptmobile.data.localruntime.LocalModelValidator.DEFAULT_MIN_SIZE_BYTES) { "This model is too small or incomplete." }
                    LocalImportPreview(name, "LiteRT-LM", size, size)
                }
                header.take(4).toByteArray().contentEquals("GGUF".toByteArray()) -> {
                    require(geniex) { "GGUF requires the separate GenieX build." }
                    require(name.endsWith(".gguf", true) && count == 8 && header[4].toInt() in 2..3 && header.drop(5).all { it == 0.toByte() }) { "Unsupported GGUF header or filename." }
                    LocalImportPreview(name, "GenieX · GGUF", size, size)
                }
                header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() -> {
                    require(name.endsWith(".zip", true) || name.endsWith(".localmodel", true)) { "Choose a .localmodel or manifest ZIP package." }
                    ZipInputStream(stream).use { archive ->
                        require(archive.nextEntry?.name == "manifest.json") { "Bundle must begin with manifest.json." }
                        manifest(archive, name, size, geniex)
                    }
                }
                else -> error("Unsupported chat model. Choose a LiteRT-LM container or a supported GenieX package; raw TFLite and task files are not chat containers.")
            }
        }

        private fun manifest(input: InputStream, name: String, size: Long?, geniex: Boolean): LocalImportPreview {
            require(geniex) { "This model bundle requires the separate GenieX build." }
            val buffer = ByteArray(262_145)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count in 1..262_144) { "Missing or oversized model manifest." }
            val model = json.decodeFromString<ModelArtifactManifest>(buffer.copyOf(count).decodeToString())
            require(model.schemaVersion in 1..2 && model.runtime in setOf(ArtifactRuntime.GENIEX_QAIRT, ArtifactRuntime.GENIEX_LLAMA_CPP)) { "Unsupported bundle runtime or manifest version." }
            require(model.files.size in 1..256 && model.files.map { it.path.lowercase() }.distinct().size == model.files.size)
            require(model.files.all { it.sizeBytes in 1..16_000_000_000L && it.sha256.matches(Regex("[a-fA-F0-9]{64}")) }) { "Every asset needs a declared size and SHA-256." }
            model.files.forEach { ModelArtifactManifest.safeFile(File("/import-preview"), it.path) }
            require(model.files.any { it.path == model.entryFile } && model.files.none { it.path == "manifest.json" }) { "Manifest entry asset is missing." }
            val total = model.files.sumOf { it.sizeBytes }
            require(total <= 32_000_000_000L && model.contextTokens > 0) { "Invalid bundle size or context metadata." }
            require(model.supportedBackends.isNotEmpty() && model.supportedBackends.all { it in setOf("cpu", "gpu", "npu") })
            return LocalImportPreview(model.familyId.ifBlank { name }, model.runtime.name.replace('_', ' '), size, total, model.supportedBackends.sorted(), model.contextTokens, model.files.size)
        }
    }
}
