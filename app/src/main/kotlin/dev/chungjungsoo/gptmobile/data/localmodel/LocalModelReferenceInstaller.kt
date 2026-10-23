package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
                val document = ModelFolderArchive.documents(context.contentResolver, source, checkCurrent)
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
                    NativeModelSource.withFile(context, uri, asset.path.substringAfterLast('/')) { file ->
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
                NativeModelSource.withFile(context, source, name) { file ->
                    if (name.endsWith(".litertlm", true)) require(LocalModelValidator.validate(file.path) is ModelValidationResult.Valid) { "Invalid or incomplete LiteRT-LM model" }
                    val described = if (name.endsWith(".gguf", true)) GgufImportAdapter.describe(file, "pending-linked-id", "0".repeat(64), checkCurrent) else null
                    val hash = digest(file)
                    assets += LinkedModelAsset(name, source.toString(), file.length(), hash)
                    manifest = described
                }
            }
            checkCurrent()
            val configuredManifest = manifest?.copy(artifactId = "", entryFile = if (folder) checkNotNull(manifest).entryFile else name, files = assets.map { ArtifactFile(it.path, it.sizeBytes, it.sha256) }, evidence = ArtifactEvidence.DISCOVERED, validations = emptyList())
            val id = linkedModelIdentity(source.toString(), assets, configuredManifest)
            val relative = LocalModelDownloadPaths.relativeDirectory(id, LocalModelLocator.LOCAL_COMMIT_HASH)
            val root = File(storage, relative)
            val existed = root.exists()
            check(root.mkdirs() || root.isDirectory)
            if (!existed) created = root
            LinkedModelSources.register(root, source, assets)
            val file = if (manifest != null) {
                val model = checkNotNull(configuredManifest).copy(artifactId = id)
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
            return LocalModelImportResult.Failure(if (failure is NativeModelSource.CannotLink) LocalModelImportResult.Failure.Reason.LINK_UNSUPPORTED else LocalModelImportResult.Failure.Reason.INVALID_MODEL, failure.message ?: "Could not link this model location")
        }
    }
}

/** Storage references and effective manifest settings are part of a warm model's identity. */
internal fun linkedModelIdentity(grant: String, assets: List<LinkedModelAsset>, manifest: ModelArtifactManifest?): String {
    val json = Json { encodeDefaults = true }
    val identity = kotlinx.serialization.json.buildJsonObject {
        put("grant", kotlinx.serialization.json.JsonPrimitive(grant))
        put("assets", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LinkedModelAsset.serializer()), assets.sortedBy { it.path }))
        put("manifest", manifest?.let { json.encodeToJsonElement(ModelArtifactManifest.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
    }
    return "linked_" + PackageDigest.sha256(identity.toString().toByteArray()).take(32)
}
