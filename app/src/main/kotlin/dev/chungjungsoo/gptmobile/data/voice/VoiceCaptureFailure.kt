package dev.chungjungsoo.gptmobile.data.voice

import java.io.IOException

/** Safe platform metadata; no recorded audio, transcript or raw platform message is exported. */
internal class LocalVoiceCaptureException(
    message: String,
    val code: String,
    val phase: String,
    val platformCode: Int? = null,
    val recorderState: Int? = null,
    val recordingState: Int? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
    /** Includes partial microphone buffers that have not yet become a decoder chunk. */
    val capturedSamples: Long = 0
) : IOException(message, cause) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "captureErrorCode" to code,
        "failedStage" to phase,
        "audioReadResult" to platformCode,
        "recorderState" to recorderState,
        "recordingState" to recordingState,
        "capturedSamples" to capturedSamples,
        "retryable" to retryable,
        "exceptionType" to cause?.javaClass?.simpleName
    )
}

/** Never restart after accepting audio: replay could silently discard part of a spoken turn. */
internal fun mayRecreateVoiceCapture(failure: LocalVoiceCaptureException, attempts: Int, capturedChunks: Int): Boolean =
    failure.retryable && attempts == 1 && capturedChunks == 0 && failure.capturedSamples == 0L
