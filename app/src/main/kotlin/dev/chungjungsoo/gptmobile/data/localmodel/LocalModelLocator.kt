package dev.chungjungsoo.gptmobile.data.localmodel

import dev.chungjungsoo.gptmobile.data.localruntime.LocalModelValidator
import dev.chungjungsoo.gptmobile.data.localruntime.ModelValidationResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Result of importing or locating a local model file.
 */
sealed interface LocalModelImportResult {
    data class Success(
        val record: LocalModelRecord,
        val absoluteFilePath: String,
        val sizeBytes: Long
    ) : LocalModelImportResult

    data class Failure(
        val reason: Reason,
        val message: String
    ) : LocalModelImportResult {
        enum class Reason {
            SOURCE_FILE_NOT_FOUND,
            SOURCE_NOT_A_FILE,
            UNREADABLE_SOURCE,
            LINK_UNSUPPORTED,
            UNSUPPORTED_FORMAT,
            INVALID_MODEL,
            RUNTIME_UNAVAILABLE,
            DESTINATION_WRITE_FAILED
        }
    }
}

/**
 * Service to locate and register locally browsed AI models on disk,
 * allowing users to provide custom weights directly without downloading
 * from Hugging Face.
 */
object LocalModelLocator {

    const val LOCAL_COMMIT_HASH = "local"
    const val LOCAL_CATALOG_PREFIX = "local_model_"

    // This importer is wired to LiteRT-LM, not a GGUF/llama.cpp runtime.
    val SUPPORTED_EXTENSIONS = setOf("litertlm")

    /**
     * Checks whether a file extension corresponds to a recognized local model type.
     */
    fun isSupportedModelFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    /**
     * Derives a safe catalogEntryId for a locally located model file.
     */
    fun generateCatalogEntryId(fileName: String): String {
        val sanitized = fileName.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_')
        return "$LOCAL_CATALOG_PREFIX${if (sanitized.isEmpty()) "custom" else sanitized}"
    }

    /**
     * Locates and validates an existing model file on disk without copying it,
     * producing a LocalModelRecord that points to the model file.
     */
    fun locateExistingModel(
        sourceFile: File,
        minSizeBytes: Long = LocalModelValidator.DEFAULT_MIN_SIZE_BYTES
    ): LocalModelImportResult {
        if (!sourceFile.exists()) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.SOURCE_FILE_NOT_FOUND,
                "File does not exist: ${sourceFile.absolutePath}"
            )
        }
        if (!sourceFile.isFile) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.SOURCE_NOT_A_FILE,
                "Path is not a regular file: ${sourceFile.absolutePath}"
            )
        }
        if (!sourceFile.canRead()) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.UNREADABLE_SOURCE,
                "File cannot be read: ${sourceFile.absolutePath}"
            )
        }
        if (!isSupportedModelFile(sourceFile.name)) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.UNSUPPORTED_FORMAT,
                "Unsupported model format: ${sourceFile.name}. Supported formats: $SUPPORTED_EXTENSIONS"
            )
        }

        when (val validation = LocalModelValidator.validate(sourceFile.absolutePath, minSizeBytes = minSizeBytes)) {
            is ModelValidationResult.Invalid -> {
                return LocalModelImportResult.Failure(
                    LocalModelImportResult.Failure.Reason.INVALID_MODEL,
                    validation.details ?: validation.reason.name
                )
            }
            is ModelValidationResult.Valid -> {
                val catalogEntryId = generateCatalogEntryId(sourceFile.name)
                val relativeDir = LocalModelDownloadPaths.relativeDirectory(catalogEntryId, LOCAL_COMMIT_HASH)
                val record = LocalModelRecord(
                    catalogEntryId = catalogEntryId,
                    commitHash = LOCAL_COMMIT_HASH,
                    fileName = sourceFile.name,
                    relativeDirectory = relativeDir,
                    status = LocalModelStatus.READY
                )
                return LocalModelImportResult.Success(
                    record = record,
                    absoluteFilePath = sourceFile.absolutePath,
                    sizeBytes = validation.sizeBytes
                )
            }
        }
    }

    /**
     * Imports a model from an input stream (e.g. from an Android SAF content URI or file stream)
     * into the local model directory hierarchy under LocalModelDownloadPaths.
     */
    fun importModel(
        inputStream: InputStream,
        fileName: String,
        targetModelsRootDir: File,
        minSizeBytes: Long = LocalModelValidator.DEFAULT_MIN_SIZE_BYTES,
        checkCurrent: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
        stagingId: String? = null
    ): LocalModelImportResult {
        if (!isSupportedModelFile(fileName)) {
            runCatching { inputStream.close() }
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.UNSUPPORTED_FORMAT,
                "Unsupported model format: $fileName. Supported formats: $SUPPORTED_EXTENSIONS"
            )
        }

        if (!LocalModelDownloadPaths.isValidPathSegment(fileName)) {
            runCatching { inputStream.close() }
            return LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.UNSUPPORTED_FORMAT, "Invalid model file name")
        }
        var catalogEntryId = generateCatalogEntryId(fileName)
        var relativeDir = LocalModelDownloadPaths.relativeDirectory(catalogEntryId, LOCAL_COMMIT_HASH)
        val targetDir = File(targetModelsRootDir, "models/.import-${importWorkspaceId(stagingId)}")
        if (stagingId != null) targetDir.deleteRecursively() // Only this persisted WorkManager job owns this directory.
        var finalFile = File(targetDir, fileName)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val targetFile = File(targetDir, ".import-${java.util.UUID.randomUUID()}-${LocalModelDownloadPaths.partialFileName(fileName)}")

        try {
            try {
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            checkCurrent()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(targetModelsRootDir.usableSpace > count + 8L * 1024 * 1024) { "Not enough storage to finish import" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            onProgress(copied)
                        }
                        output.fd.sync()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                targetDir.deleteRecursively()
                throw cancelled
            } catch (e: Exception) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                return LocalModelImportResult.Failure(
                    LocalModelImportResult.Failure.Reason.DESTINATION_WRITE_FAILED,
                    "Failed to write file to local models directory: ${e.message}"
                )
            }

            when (val validation = LocalModelValidator.validate(targetFile.absolutePath, minSizeBytes = minSizeBytes)) {
                is ModelValidationResult.Invalid -> {
                    targetFile.delete()
                    return LocalModelImportResult.Failure(
                        LocalModelImportResult.Failure.Reason.INVALID_MODEL,
                        validation.details ?: validation.reason.name
                    )
                }
                is ModelValidationResult.Valid -> {
                    checkCurrent()
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    catalogEntryId = "local_${hash.take(32)}"
                    relativeDir = LocalModelDownloadPaths.relativeDirectory(catalogEntryId, LOCAL_COMMIT_HASH)
                    val destination = File(targetModelsRootDir, relativeDir)
                    check(destination.mkdirs() || destination.isDirectory)
                    // Keep the source filename (including Qualcomm/SoC suffix).
                    // Content-hash identity still deduplicates renamed copies.
                    val existing = destination.listFiles().orEmpty()
                        .filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) && it.length() == validation.sizeBytes }
                        .firstOrNull { PackageDigest.sha256(it, checkCurrent) == hash }
                    finalFile = existing ?: File(destination, fileName)
                    if (existing != null) {
                        targetFile.delete()
                    } else if (finalFile.exists() || !targetFile.renameTo(finalFile)) {
                        targetFile.delete()
                        return LocalModelImportResult.Failure(
                            LocalModelImportResult.Failure.Reason.DESTINATION_WRITE_FAILED,
                            "Could not safely finalize imported model"
                        )
                    }
                    targetDir.deleteRecursively()
                    val record = LocalModelRecord(
                        catalogEntryId = catalogEntryId,
                        commitHash = LOCAL_COMMIT_HASH,
                        fileName = finalFile.name,
                        relativeDirectory = relativeDir,
                        status = LocalModelStatus.READY
                    )
                    return LocalModelImportResult.Success(
                        record = record,
                        absoluteFilePath = finalFile.absolutePath,
                        sizeBytes = validation.sizeBytes
                    )
                }
            }
        } finally {
            targetDir.deleteRecursively()
        }
    }

    /**
     * Imports a model from an existing local file by copying it into the managed local model directory.
     */
    fun importFromFile(
        sourceFile: File,
        targetModelsRootDir: File,
        minSizeBytes: Long = LocalModelValidator.DEFAULT_MIN_SIZE_BYTES
    ): LocalModelImportResult {
        if (!sourceFile.exists()) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.SOURCE_FILE_NOT_FOUND,
                "Source file not found: ${sourceFile.absolutePath}"
            )
        }
        if (!sourceFile.canRead()) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.UNREADABLE_SOURCE,
                "Source file is unreadable: ${sourceFile.absolutePath}"
            )
        }
        return importModel(
            inputStream = FileInputStream(sourceFile),
            fileName = sourceFile.name,
            targetModelsRootDir = targetModelsRootDir,
            minSizeBytes = minSizeBytes
        )
    }
}
