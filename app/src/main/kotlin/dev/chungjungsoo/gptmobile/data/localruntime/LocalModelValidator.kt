package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File

sealed class ModelValidationResult {
    data class Valid(val file: File, val sizeBytes: Long) : ModelValidationResult()
    sealed class Invalid : ModelValidationResult() {
        enum class Reason {
            NOT_FOUND,
            FILE_TOO_SMALL,
            INVALID_FORMAT
        }
        data class Details(
            val reason: Reason,
            val details: String
        )
    }
}

object LocalModelValidator {
    fun validate(modelPath: String): ModelValidationResult {
        val file = File(modelPath)
        if (!file.exists()) {
            return ModelValidationResult.Invalid.Details(
                ModelValidationResult.Invalid.Reason.NOT_FOUND,
                "Model file not found at path: $modelPath"
            )
        }
        if (file.length() < 1024) {
            return ModelValidationResult.Invalid.Details(
                ModelValidationResult.Invalid.Reason.FILE_TOO_SMALL,
                "Model file is too small: ${file.length()} bytes"
            )
        }
        return ModelValidationResult.Valid(file, file.length())
    }
}