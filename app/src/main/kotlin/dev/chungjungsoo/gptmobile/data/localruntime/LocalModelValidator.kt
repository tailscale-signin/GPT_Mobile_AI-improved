package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Result of local model file integrity verification.
 */
sealed interface ModelValidationResult {
    data class Valid(
        val file: File,
        val sizeBytes: Long,
        val sha256Hex: String? = null
    ) : ModelValidationResult

    data class Invalid(
        val file: File?,
        val reason: Reason,
        val details: String? = null
    ) : ModelValidationResult {
        enum class Reason {
            NOT_FOUND,
            NOT_A_FILE,
            CANNOT_READ,
            FILE_TOO_SMALL,
            CHECKSUM_MISMATCH,
            CORRUPTED_HEADER
        }
    }
}

/**
 * Validates local model weights before initialization by LiteRT-LM to avoid native crashes (SIGSEGV).
 */
object LocalModelValidator {
    // Most quantized LLM weights are at least 50MB. A file smaller than 10MB is almost certainly
    // an incomplete download, an HTML error page, or a truncated file.
    const val DEFAULT_MIN_SIZE_BYTES = 10L * 1024L * 1024L // 10 MB

    /**
     * Validates that the model file exists, is non-empty, meets the minimum size threshold,
     * and optionally verifies against expected SHA-256 hash or magic bytes.
     */
    fun validate(
        modelPath: String,
        expectedSha256: String? = null,
        minSizeBytes: Long = DEFAULT_MIN_SIZE_BYTES,
        verifyTFLiteHeader: Boolean = false
    ): ModelValidationResult {
        val file = File(modelPath)

        if (!file.exists()) {
            return ModelValidationResult.Invalid(file, ModelValidationResult.Invalid.Reason.NOT_FOUND, "File does not exist: $modelPath")
        }

        if (!file.isFile) {
            return ModelValidationResult.Invalid(file, ModelValidationResult.Invalid.Reason.NOT_A_FILE, "Path is not a regular file")
        }

        if (!file.canRead()) {
            return ModelValidationResult.Invalid(file, ModelValidationResult.Invalid.Reason.CANNOT_READ, "File is not readable")
        }

        val size = file.length()
        if (size < minSizeBytes) {
            return ModelValidationResult.Invalid(
                file,
                ModelValidationResult.Invalid.Reason.FILE_TOO_SMALL,
                "File size $size bytes is below minimum required $minSizeBytes bytes"
            )
        }

        // TFLite / FlatBuffer header check: standard TFLite models start with magic string "TFL3" at byte offset 4
        if (verifyTFLiteHeader && file.extension.equals("tflite", ignoreCase = true)) {
            val headerValid = runCatching {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8)
                    val read = fis.read(buffer)
                    if (read >= 8) {
                        val magic = String(buffer, 4, 4, Charsets.US_ASCII)
                        magic == "TFL3"
                    } else {
                        false
                    }
                }
            }.getOrDefault(false)

            if (!headerValid) {
                return ModelValidationResult.Invalid(
                    file,
                    ModelValidationResult.Invalid.Reason.CORRUPTED_HEADER,
                    "Invalid TFLite header (missing TFL3 identifier)"
                )
            }
        }

        var calculatedSha256: String? = null
        if (!expectedSha256.isNullOrBlank()) {
            calculatedSha256 = computeSha256(file)
            if (!calculatedSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                return ModelValidationResult.Invalid(
                    file,
                    ModelValidationResult.Invalid.Reason.CHECKSUM_MISMATCH,
                    "Expected SHA-256 $expectedSha256 but got $calculatedSha256"
                )
            }
        }

        return ModelValidationResult.Valid(
            file = file,
            sizeBytes = size,
            sha256Hex = calculatedSha256
        )
    }

    /**
     * Compute SHA-256 checksum of a file stream.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
