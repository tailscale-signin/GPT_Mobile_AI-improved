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
            UNSUPPORTED_FORMAT,
            INVALID_MODEL,
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

    val SUPPORTED_EXTENSIONS = setOf(
        "bin",
        "task",
        "tflite",
        "litertmodel",
        "gguf"
    )

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
        minSizeBytes: Long = LocalModelValidator.DEFAULT_MIN_SIZE_BYTES
    ): LocalModelImportResult {
        if (!isSupportedModelFile(fileName)) {
            return LocalModelImportResult.Failure(
                LocalModelImportResult.Failure.Reason.UNSUPPORTED_FORMAT,
                "Unsupported model format: $fileName. Supported formats: $SUPPORTED_EXTENSIONS"
            )
        }

        val catalogEntryId = generateCatalogEntryId(fileName)
        val relativeDir = LocalModelDownloadPaths.relativeDirectory(catalogEntryId, LOCAL_COMMIT_HASH)
        val targetDir = File(targetModelsRootDir, relativeDir)
        val targetFile = File(targetDir, fileName)

        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
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
                val record = LocalModelRecord(
                    catalogEntryId = catalogEntryId,
                    commitHash = LOCAL_COMMIT_HASH,
                    fileName = fileName,
                    relativeDirectory = relativeDir,
                    status = LocalModelStatus.READY
                )
                return LocalModelImportResult.Success(
                    record = record,
                    absoluteFilePath = targetFile.absolutePath,
                    sizeBytes = validation.sizeBytes
                )
            }
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
