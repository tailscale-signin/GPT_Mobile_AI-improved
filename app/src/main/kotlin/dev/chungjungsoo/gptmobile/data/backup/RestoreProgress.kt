package dev.chungjungsoo.gptmobile.data.backup

/** Stages correspond to real restore operations, not estimated elapsed time. */
enum class RestorePhase(val label: String) {
    READING("Opening backup"),
    CONVERTING("Converting older backup"),
    DECRYPTING("Decrypting backup"),
    EXTRACTING("Extracting files"),
    VERIFYING("Checking backup data"),
    PREPARING("Preparing conversations"),
    APPLYING("Restoring selected data"),
    ROLLING_BACK("Reverting incomplete restore"),
    RESEARCH("Restoring research & statistics"),
    FINALIZING("Finishing restore")
}

/** Byte counts are real only for decrypt/extract; other stages remain indeterminate. */
data class RestoreProgress(
    val phase: RestorePhase,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) {
            (completedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }
}
