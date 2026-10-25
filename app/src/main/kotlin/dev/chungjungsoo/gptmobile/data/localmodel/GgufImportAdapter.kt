package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString

/** Self-contained text GGUF only; models needing a projector or shards use a manifest bundle. */
internal object GgufImportAdapter {
    @Volatile private var metadataDirectory: File? = null
    fun configureMetadataCache(directory: File) {
        metadataDirectory = directory
    }
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private class InspectionFlight {
        val operationId = java.util.UUID.randomUUID().toString()
        val result = java.util.concurrent.CompletableFuture<Map<String, String>>()
    }
    private val inspections = java.util.concurrent.ConcurrentHashMap<String, InspectionFlight>()

    fun inspect(file: File, checkCurrent: () -> Unit = {}): Map<String, String> {
        inspection@ while (true) {
            checkCurrent()
            val identityStarted = System.nanoTime()
            val identity = PackageDigest.installedFileIdentity(file)
            val identityMs = (System.nanoTime() - identityStarted) / 1_000_000
            identity?.let { metadataCache[it] }?.let { cached ->
                checkCurrent()
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "GGUF_METADATA_CACHE_HIT · cache=memory · identityMs=$identityMs · metadataParseMs=0 · metadataBytesRead=0")
                return cached
            }
            if (identity == null) return inspectUncached(file, null, java.util.UUID.randomUUID().toString(), checkCurrent)
            val mine = InspectionFlight()
            val existing = inspections.putIfAbsent(identity, mine)
            if (existing == null) {
                try {
                    val result = metadataCache[identity] ?: inspectUncached(file, identity, mine.operationId, checkCurrent)
                    mine.result.complete(result)
                    return result
                } catch (failure: Throwable) {
                    if (failure !is kotlinx.coroutines.CancellationException) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "METADATA_PARSE_FAILED · operationId=${mine.operationId} · stage=metadata · errorType=${failure.javaClass.simpleName}", "E")
                    mine.result.completeExceptionally(failure)
                    throw failure
                } finally {
                    inspections.remove(identity, mine)
                }
            }
            val waitStarted = System.nanoTime()
            try {
                while (true) {
                    checkCurrent()
                    val result = try {
                        existing.result.get(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        continue
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        inspections.remove(identity, existing)
                        checkCurrent()
                        continue@inspection
                    }
                    checkCurrent()
                    check(identity == PackageDigest.installedFileIdentity(file)) { "Model changed during metadata inspection" }
                    val waitMs = (System.nanoTime() - waitStarted) / 1_000_000
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "METADATA_REQUEST_COALESCED · operationId=${existing.operationId} · cache=shared_inflight · identityMs=$identityMs · metadataWaitMs=$waitMs · metadataParseMs=0 · metadataBytesRead=0")
                    return result
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw kotlinx.coroutines.CancellationException("Metadata wait interrupted", interrupted)
            } catch (failure: java.util.concurrent.ExecutionException) {
                val cause = failure.cause ?: failure
                // One caller cancelling its parse cannot revoke another caller's
                // authorization. Retry the flight after the caller's own check.
                if (cause is kotlinx.coroutines.CancellationException) {
                    inspections.remove(identity, existing)
                    checkCurrent()
                    continue
                }
                throw cause
            }
        }
    }

    private fun inspectUncached(file: File, identity: String?, operationId: String, checkCurrent: () -> Unit): Map<String, String> {
        val cacheStarted = System.nanoTime()
        val persisted = identity?.let { key -> metadataDirectory?.let { File(it, PackageDigest.sha256(key.toByteArray()) + ".json") } }
        persisted?.takeIf { it.isFile && it.length() <= 2 * 1024 * 1024 }?.let { cache ->
            val saved = runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(cache.readText()) }.getOrNull()
            if (saved != null && identity == PackageDigest.installedFileIdentity(file)) {
                checkCurrent()
                metadataCache[checkNotNull(identity)] = saved
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "GGUF_METADATA_CACHE_HIT · operationId=$operationId · cache=disk · cacheReadMs=${(System.nanoTime() - cacheStarted) / 1_000_000} · cacheBytesRead=${cache.length()} · metadataParseMs=0 · metadataBytesRead=0")
                return saved
            }
        }
        val started = System.nanoTime()
        val size = file.length()
        var position = 0L
        fun advance(count: Long) {
            require(count >= 0 && count <= minOf(size, 64L * 1024 * 1024) - position) { "GGUF metadata is oversized or truncated" }
            position += count
        }
        val result = DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { input ->
            fun u32(): Long {
                advance(4)
                return java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            }
            fun u64(): Long {
                advance(8)
                return java.lang.Long.reverseBytes(input.readLong()).also { require(it >= 0) }
            }
            fun skip(bytes: Long) {
                advance(bytes)
                var remaining = bytes
                while (remaining > 0) {
                    checkCurrent()
                    val skipped = input.skip(minOf(remaining, 256 * 1024L))
                    if (skipped == 0L) {
                        input.readByte()
                        remaining--
                    } else {
                        remaining -= skipped
                    }
                }
            }
            fun text(retain: Boolean): String? {
                val bytes = u64()
                require(bytes <= 1_048_576) { "GGUF metadata string is oversized" }
                if (!retain) {
                    skip(bytes)
                    return null
                }
                advance(bytes)
                return ByteArray(bytes.toInt()).also(input::readFully).decodeToString()
            }
            require(u32() == 0x46554747L) { "Not a GGUF file" }
            require(u32() in 2..3) { "Unsupported GGUF version" }
            require(u64() in 1..100_000)
            val count = u64().also { require(it in 1..100_000) }
            val widths = mapOf(0L to 1L, 1L to 1L, 7L to 1L, 2L to 2L, 3L to 2L, 4L to 4L, 5L to 4L, 6L to 4L, 10L to 8L, 11L to 8L, 12L to 8L)
            buildMap {
                repeat(count.toInt()) {
                    checkCurrent()
                    val key = checkNotNull(text(true))
                    val keep = key in setOf("general.architecture", "general.name", "general.file_type", "tokenizer.chat_template", "split.count") ||
                        listOf(".context_length", ".block_count", ".attention.head_count", ".attention.head_count_kv", ".embedding_length", ".attention.key_length", ".attention.value_length").any(key::endsWith)
                    val type = u32()
                    val value = when {
                        type == 8L -> text(keep)
                        type == 9L -> {
                            val element = u32()
                            val length = u64().also { require(it <= 1_000_000) }
                            when {
                                element == 8L -> repeat(length.toInt()) {
                                    checkCurrent()
                                    text(false)
                                }
                                element in widths -> skip(Math.multiplyExact(length, widths.getValue(element)))
                                else -> error("Unknown or nested GGUF metadata array type")
                            }
                            null
                        }
                        type !in widths -> error("Unknown GGUF metadata type")
                        !keep || type in setOf(6L, 12L) -> {
                            skip(widths.getValue(type))
                            null
                        }
                        type in setOf(0L, 1L, 7L) -> {
                            advance(1)
                            input.readUnsignedByte().toString()
                        }
                        type in setOf(2L, 3L) -> {
                            advance(2)
                            java.lang.Short.reverseBytes(input.readShort()).toString()
                        }
                        type in setOf(4L, 5L) -> u32().toString()
                        else -> u64().toString()
                    }
                    if (keep && value != null) put(key, value)
                }
            }
        }
        val parseMs = (System.nanoTime() - started) / 1_000_000
        checkCurrent()
        val persistenceStarted = System.nanoTime()
        if (identity != null) {
            check(identity == PackageDigest.installedFileIdentity(file)) { "Model changed during metadata inspection" }
            if (metadataCache.size >= 32) metadataCache.clear()
            metadataCache[identity] = result
            persisted?.let { cache ->
                runCatching {
                    check(cache.parentFile!!.isDirectory || cache.parentFile!!.mkdirs())
                    val temporary = File.createTempFile("metadata-", ".tmp", cache.parentFile)
                    try {
                        temporary.writeText(kotlinx.serialization.json.Json.encodeToString(result))
                        java.nio.file.Files.move(temporary.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
        val cacheWriteMs = (System.nanoTime() - persistenceStarted) / 1_000_000
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "GGUF_METADATA · operationId=$operationId · durationMs=${(System.nanoTime() - started) / 1_000_000} · metadataParseMs=$parseMs · cacheWriteMs=$cacheWriteMs · metadataBytesRead=$position · cache=miss · identity=${if (identity == null) "unavailable" else "strong"}")
        dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory.record("METADATA_PARSED", mapOf("operationId" to operationId, "metadataParseMs" to parseMs.toString(), "cacheWriteMs" to cacheWriteMs.toString(), "metadataBytesRead" to position.toString(), "cache" to "miss"))
        return result
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
