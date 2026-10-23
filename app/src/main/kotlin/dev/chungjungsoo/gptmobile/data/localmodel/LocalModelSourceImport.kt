package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.Context
import android.provider.OpenableColumns
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import java.io.File
import kotlinx.serialization.json.Json

/** Persisted mode is explicit: an unsuccessful link never silently becomes a multi-GB copy. */
internal object LocalModelSourceImport {
    fun linkProblem(context: Context, source: LocalModelSource, name: String, checkCurrent: () -> Unit): String? = try {
        if (source.folder) {
            val document = ModelFolderArchive.documents(context.contentResolver, source.uri, checkCurrent)
            val bytes = context.contentResolver.openInputStream(document("manifest.json"))?.use(::readModelReferenceMetadata) ?: error("Folder access was lost. Select it again.")
            val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<ModelArtifactManifest>(bytes.decodeToString())
            require(manifest.files.size in 1..256)
            manifest.files.forEach {
                checkCurrent()
                NativeModelSource.withFile(context, document(it.path), it.path.substringAfterLast('/')) { }
            }
        } else {
            NativeModelSource.withFile(context, source.uri, name) { }
        }
        null
    } catch (failure: NativeModelSource.CannotLink) {
        failure.detail
    }

    suspend fun execute(context: Context, repository: LocalModelRepository, source: LocalModelSource, jobId: String, checkCurrent: () -> Unit, progress: (Long) -> Unit): LocalModelImportResult {
        checkCurrent()
        if (source.mode == LocalModelSource.Mode.LINK) return repository.linkSource(source.uri, source.folder, checkCurrent, progress)
        val resolver = context.contentResolver
        if (!source.folder) {
            val name = resolver.query(source.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("Source filename is unavailable")
            require(LocalModelDownloadPaths.isValidPathSegment(name) && (name.endsWith(".litertlm", true) || name.endsWith(".gguf", true))) { "Choose a .litertlm or .gguf model file." }
            return (resolver.openInputStream(source.uri) ?: error("Source access was lost. Select it again.")).use {
                repository.importPersisted(it, name, jobId, checkCurrent, progress)
            }
        }
        // Only this persisted job owns the archive. Remove it on success, failure and cancellation.
        val archive = File(context.cacheDir, "model-folder-${importWorkspaceId(jobId)}.localmodel")
        try {
            check(!archive.exists() || archive.delete()) { "Could not clear this import job’s interrupted archive" }
            ModelFolderArchive.copy(resolver, source.uri, archive, checkCurrent, progress)
            return archive.inputStream().use { repository.importPersisted(it, archive.name, jobId, checkCurrent, progress) }
        } finally {
            archive.delete()
        }
    }
}
