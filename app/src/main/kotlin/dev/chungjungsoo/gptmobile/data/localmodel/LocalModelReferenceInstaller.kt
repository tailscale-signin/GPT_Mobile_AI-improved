package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import dev.chungjungsoo.gptmobile.data.localruntime.LocalModelValidator
import dev.chungjungsoo.gptmobile.data.localruntime.ModelValidationResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Link selected documents in place. ZIPs must be extracted by their owner, never copied implicitly. */
internal object LocalModelReferenceInstaller {
    private val json = Json { ignoreUnknownKeys = true }

    fun install(context: Context, source: Uri, folder: Boolean, storage: File, checkCurrent: () -> Unit, progress: (Long) -> Unit): LocalModelImportResult {
        LinkedModelSources.initialize(context)
        var created: File? = null
        try {
            require(source.scheme == "content") { "Choose an on-device model using the file picker." }
            val assets = mutableListOf<LinkedModelAsset>()
            var manifest: ModelArtifactManifest? = null
            var verifiedBytes = 0L
            fun digest(file: File): String = file.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(262_144)
                while (true) {
                    checkCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                    verifiedBytes += count
                    require(verifiedBytes <= 32_000_000_000L) { "Model assets exceed the supported size limit" }
                    progress(verifiedBytes)
                }
                hash.digest().joinToString("") { "%02x".format(it) }
            }
            val name: String
            if (folder) {
                val root = DocumentsContract.getTreeDocumentId(source)
                val children = mutableMapOf<String, Map<String, String>>()
                fun document(path: String): Uri {
                    ModelArtifactManifest.safeFile(File(context.cacheDir, "reference-path-check"), path)
                    var current = root
                    path.split('/').forEach { segment ->
                        current = children.getOrPut(current) {
                            val query = DocumentsContract.buildChildDocumentsUriUsingTree(source, current)
                            context.contentResolver.query(query, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                                buildMap {
                                    while (cursor.moveToNext()) {
                                        checkCurrent()
                                        require(size < 1024 && put(cursor.getString(0), cursor.getString(1)) == null) { "Folder has too many or ambiguous entries" }
                                    }
                                }
                            } ?: error("Folder access was lost. Select it again.")
                        }[segment] ?: error("Missing declared model asset: $path")
                    }
                    return DocumentsContract.buildDocumentUriUsingTree(source, current)
                }
                val bytes = context.contentResolver.openInputStream(document("manifest.json"))?.use { readModelReferenceMetadata(it) } ?: error("Cannot read model manifest")
                require(bytes.size <= 262_144) { "Model manifest is oversized" }
                val declared = json.decodeFromString<ModelArtifactManifest>(bytes.decodeToString())
                manifest = declared
                require(declared.runtime in setOf(ArtifactRuntime.LLAMA_CPP, ArtifactRuntime.GENIEX_QAIRT, ArtifactRuntime.GENIEX_LLAMA_CPP)) { "Unsupported folder runtime" }
                dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.requireRuntime(declared.runtime)
                require(declared.files.size in 1..256 && declared.files.map { it.path.lowercase() }.distinct().size == declared.files.size && declared.files.none { it.path == "manifest.json" }) { "Invalid asset inventory" }
                declared.files.forEach { asset ->
                    require(asset.sizeBytes in 1..16_000_000_000L && asset.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Assets need a size and SHA-256" }
                    val uri = document(asset.path)
                    withSeekableSource(context, uri, asset.path.substringAfterLast('/')) { file ->
                        require(file.length() == asset.sizeBytes) { "Truncated model asset: ${asset.path}" }
                        val actual = digest(file)
                        require(actual.equals(asset.sha256, true)) { "Model asset checksum mismatch: ${asset.path}" }
                        assets += LinkedModelAsset(asset.path, uri.toString(), file.length(), actual)
                    }
                }
                name = "manifest.json"
            } else {
                name = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("Source filename is unavailable")
                require(LocalModelDownloadPaths.isValidPathSegment(name) && (name.endsWith(".litertlm", true) || name.endsWith(".gguf", true))) { "Link a .litertlm or .gguf file. Extract ZIP/.localmodel packages and select their model folder; local weights are not copied." }
                withSeekableSource(context, source, name) { file ->
                    if (name.endsWith(".litertlm", true)) require(LocalModelValidator.validate(file.path) is ModelValidationResult.Valid) { "Invalid or incomplete LiteRT-LM model" }
                    val described = if (name.endsWith(".gguf", true)) GgufImportAdapter.describe(file, "pending-linked-id", "0".repeat(64), checkCurrent) else null
                    val hash = digest(file)
                    assets += LinkedModelAsset(name, source.toString(), file.length(), hash)
                    manifest = described
                }
            }
            checkCurrent()
            val id = "linked_${PackageDigest.sha256((source.toString() + assets.joinToString { it.path + ":" + it.sha256 }).toByteArray()).take(32)}"
            val relative = LocalModelDownloadPaths.relativeDirectory(id, LocalModelLocator.LOCAL_COMMIT_HASH)
            val root = File(storage, relative)
            val existed = root.exists()
            check(root.mkdirs() || root.isDirectory)
            if (!existed) created = root
            LinkedModelSources.register(root, source, assets)
            val file = if (manifest != null) {
                val model = checkNotNull(manifest).copy(artifactId = id, entryFile = if (folder) checkNotNull(manifest).entryFile else name, files = assets.map { ArtifactFile(it.path, it.sizeBytes, it.sha256) }, evidence = ArtifactEvidence.DISCOVERED, validations = emptyList())
                model.validate(root, checkCurrent = checkCurrent)
                File(root, "manifest.json").also { ArtifactManifestStore.write(it, model) }
            } else {
                // This is an app-owned reference marker, not a disguised copy of model bytes.
                File(root, name).also { marker ->
                    marker.writeText("{\"linkedModel\":1}")
                    LinkedModelSources.resolveFile(marker, checkCurrent)
                }
            }
            checkCurrent()
            return LocalModelImportResult.Success(LocalModelRecord(id, LocalModelLocator.LOCAL_COMMIT_HASH, file.name, relative, LocalModelStatus.READY), file.path, assets.sumOf { it.sizeBytes })
        } catch (cancelled: CancellationException) {
            created?.let {
                LinkedModelSources.forget(it)
                it.deleteRecursively()
            }
            throw cancelled
        } catch (failure: Exception) {
            created?.let {
                LinkedModelSources.forget(it)
                it.deleteRecursively()
            }
            return LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.INVALID_MODEL, failure.message ?: "Could not link this model location")
        }
    }

    /** Reject pipes and cloud streams that cannot be memory-mapped; do not fall back to a copy. */
    private fun <T> withSeekableSource(context: Context, uri: Uri, name: String, block: (File) -> T): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Source access was lost. Select it again.")
        descriptor.use {
            val stat = Os.fstat(it.fileDescriptor)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..16_000_000_000L) { "This source cannot be read in place. Choose an on-device, seekable model file." }
            val directory = File(context.cacheDir, "model-link-check-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
            val alias = File(directory, name)
            try {
                Os.symlink("/proc/self/fd/${it.fd}", alias.path)
                try {
                    alias.inputStream().channel.use { channel ->
                        channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, minOf(4096L, stat.st_size)).get(0)
                    }
                } catch (failure: Exception) {
                    throw IllegalStateException("This provider cannot supply a native-readable model in place. Choose an on-device file in Documents or Downloads; no model copy was created.", failure)
                }
                return block(alias)
            } finally {
                alias.delete()
                directory.delete()
            }
        }
    }
}
