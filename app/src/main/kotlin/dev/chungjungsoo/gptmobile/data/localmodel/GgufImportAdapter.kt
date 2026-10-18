package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Self-contained text GGUF only; models needing a projector or shards use a manifest bundle. */
internal object GgufImportAdapter {
    fun inspect(file: File, checkCurrent: () -> Unit = {}): Map<String, String> = RandomAccessFile(file, "r").use { input ->
        require(input.readInt() == 0x47475546) { "Not a GGUF file" }
        fun u32() = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun u64() = java.lang.Long.reverseBytes(input.readLong()).also { require(it >= 0) }
        fun text(): String {
            val size = u64()
            require(size <= 1_048_576 && input.filePointer + size <= input.length()) { "GGUF metadata is oversized or truncated" }
            return ByteArray(size.toInt()).also(input::readFully).decodeToString()
        }
        require(u32() in 2..3) { "Unsupported GGUF version" }
        val tensors = u64()
        require(tensors in 1..100_000)
        val count = u64()
        require(count in 1..100_000)
        fun value(type: Long, depth: Int = 0): String? {
            checkCurrent()
            require(depth <= 1 && input.filePointer <= 64L * 1024 * 1024) { "GGUF metadata exceeds inspection bounds" }
            return when (type) {
                0L, 1L, 7L -> input.readUnsignedByte().toString()
                2L, 3L -> java.lang.Short.reverseBytes(input.readShort()).toString()
                4L, 5L -> u32().toString()
                6L -> {
                    input.readInt()
                    null
                }
                8L -> text()
                9L -> {
                    val element = u32()
                    val size = u64()
                    require(size <= 1_000_000)
                    repeat(size.toInt()) { value(element, depth + 1) }
                    null
                }
                10L, 11L -> u64().toString()
                12L -> {
                    input.readLong()
                    null
                }
                else -> error("Unknown GGUF metadata type")
            }
        }
        buildMap {
            repeat(count.toInt()) {
                val key = text()
                val result = value(u32())
                if (result != null && (key in setOf("general.architecture", "general.name", "general.file_type", "tokenizer.chat_template", "split.count") || key.endsWith(".context_length"))) put(key, result)
            }
        }
    }

    fun describe(model: File, id: String, digest: String, checkCurrent: () -> Unit = {}): ModelArtifactManifest {
        dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.requireRuntime(ArtifactRuntime.LLAMA_CPP)
        val metadata = inspect(model, checkCurrent)
        val architecture = requireNotNull(metadata["general.architecture"]) { "GGUF architecture is missing" }
        require(architecture !in setOf("clip", "whisper", "wavtokenizer", "mamba")) { "This artifact needs a qualified manifest and its exact companion assets" }
        require((metadata["split.count"]?.toIntOrNull() ?: 1) == 1) { "Import all GGUF shards using a manifest bundle" }
        require(!metadata["tokenizer.chat_template"].isNullOrBlank()) { "No embedded chat template; use an explicit manifest package" }
        val tokens = metadata["$architecture.context_length"]?.toIntOrNull()?.takeIf { it > 0 } ?: error("Context metadata missing; use a manifest bundle")
        return ModelArtifactManifest(schemaVersion = 3, familyId = metadata["general.name"]?.take(160) ?: architecture, artifactId = id, runtime = ArtifactRuntime.LLAMA_CPP, format = "gguf", entryFile = model.name, files = listOf(ArtifactFile(model.name, model.length(), digest)), contextTokens = tokens, supportedBackends = dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.gguf(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()).backends, architecture = architecture, templateId = GgufChatTemplateIdentity.describe(metadata["tokenizer.chat_template"]), weightPrecision = metadata["general.file_type"]?.let { "GGUF type $it" }, runtimeVersion = dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.GGUF_REVISION)
    }

    fun install(input: InputStream, root: File, checkCurrent: () -> Unit, progress: (Long) -> Unit, stagingId: String? = null): LocalModelImportResult {
        if (!dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.ggufPackaged) {
            input.close()
            return LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.RUNTIME_UNAVAILABLE, "This app build does not include a compatible GGUF runtime")
        }
        val staging = File(root, "models/.gguf-${importWorkspaceId(stagingId)}")
        if (stagingId != null) staging.deleteRecursively() // Only this persisted WorkManager job owns this directory.
        return try {
            dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.requireRuntime(ArtifactRuntime.LLAMA_CPP)
            check(staging.mkdirs())
            val model = File(staging, "model.gguf")
            model.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var bytes = 0L
                while (true) {
                    checkCurrent()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(bytes + read <= 32_000_000_000L && root.usableSpace > read + 8L * 1024 * 1024) { "Insufficient import storage" }
                    output.write(buffer, 0, read)
                    bytes += read
                    progress(bytes)
                }
                output.fd.sync()
            }
            val described = describe(model, "pending-import-id", "0".repeat(64), checkCurrent)
            val digest = PackageDigest.sha256(model, checkCurrent)
            val id = "local_gguf_${digest.take(32)}"
            val manifest = described.copy(artifactId = id, files = listOf(ArtifactFile(model.name, model.length(), digest)))
            manifest.validate(staging, checkCurrent = checkCurrent)
            ArtifactManifestStore.write(File(staging, "manifest.json"), manifest)
            val relative = LocalModelDownloadPaths.relativeDirectory(id, LocalModelLocator.LOCAL_COMMIT_HASH)
            val destination = File(root, relative)
            check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
            checkCurrent()
            if (destination.exists()) ArtifactManifestStore.read(File(destination, "manifest.json")).validate(destination, checkCurrent = checkCurrent) else Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            LocalModelImportResult.Success(LocalModelRecord(id, LocalModelLocator.LOCAL_COMMIT_HASH, "manifest.json", relative, LocalModelStatus.READY), File(destination, "manifest.json").absolutePath, manifest.files.sumOf { it.sizeBytes })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.INVALID_MODEL, error.message ?: "GGUF import failed")
        } finally {
            input.close()
            staging.deleteRecursively()
        }
    }
}
