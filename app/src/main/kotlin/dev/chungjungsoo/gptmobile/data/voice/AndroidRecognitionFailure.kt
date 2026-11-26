package dev.chungjungsoo.gptmobile.data.voice

/** The platform callback belongs to this capture attempt, not the session's latest snapshot. */
internal class AndroidRecognitionFailure(
    val platformError: Int?,
    val stage: Stage,
    val dispatched: Boolean,
    private val attemptId: String,
    private val localBackoffMillis: Long
) : IllegalStateException("Android speech recognition failed during ${stage.name}") {
    enum class Stage(val reportPhase: String) {
        SUPPORT_CHECK("precheck"),
        STARTING("capture_start"),
        RECOGNITION("voice_recognition")
    }

    /** Existing closed report fields retain causality without exporting the raw attempt identity. */
    fun reportFields(retryScheduled: Boolean): Map<String, Any?> = buildMap {
        put("failed_stage", stage.reportPhase)
        put("operation", "voice_recognition")
        put("dispatched", dispatched)
        put("causal_id", attemptId)
        put("causal_provenance", "EXPLICIT_CAUSE")
        put("recovery_observation", if (retryScheduled) "RETRY_SCHEDULED" else "TERMINAL")
        if (localBackoffMillis > 0) {
            put("retry_delay_ms", localBackoffMillis)
            // Android supplies no reset deadline here. This is the app's admission pacing.
            put("retry_delay_source", "LOCAL_BACKOFF")
        }
    }
}
