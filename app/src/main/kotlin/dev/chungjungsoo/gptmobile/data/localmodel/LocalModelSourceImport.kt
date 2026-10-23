package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.ContentResolver
import android.provider.OpenableColumns
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository

/** Copying is an explicit user choice. Linking never silently duplicates model weights. */
internal suspend fun importLocalModelSource(
    repository: LocalModelRepository,
    resolver: ContentResolver,
    source: LocalModelSource,
    jobId: String,
    checkCurrent: () -> Unit,
    progress: (Long) -> Unit
): LocalModelImportResult {
    checkCurrent()
    if (!source.copyToApp) return repository.linkSource(source.uri, source.folder, checkCurrent, progress)
    require(!source.folder) { "To copy a model, select its .litertlm or .gguf file." }
    require(source.uri.scheme == "content") { "Choose a model using the file picker." }
    val name = resolver.query(source.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: error("Source filename is unavailable. Select the model again.")
    require(LocalModelDownloadPaths.isValidPathSegment(name) && (name.endsWith(".litertlm", true) || name.endsWith(".gguf", true))) {
        "Choose a .litertlm or .gguf model file to copy."
    }
    // Read the granted stream directly; reopening /proc/self/fd can fail on otherwise
    // readable document providers. The existing installers validate and atomically publish it.
    return (resolver.openInputStream(source.uri) ?: error("Source access was lost. Select the model again.")).use { input ->
        repository.importPersisted(input, name, jobId, checkCurrent, progress)
    }
}
