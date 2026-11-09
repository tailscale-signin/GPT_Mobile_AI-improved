package dev.chungjungsoo.gptmobile.data.memory

/** WorkManager success can acknowledge a durable deferment without completing enrichment. */
internal data class MemoryEnrichmentOutcome(val status: String, val reason: String) {
    val enrichmentComplete: Boolean = status in setOf("SUCCESS", "PROCESSED_NO_NEW_FACTS") ||
        status == "SKIPPED" &&
        reason in setOf("NO_ELIGIBLE_FACTS", "PROCESSED_NO_NEW_FACTS")
    val deferred: Boolean = status in setOf("WAITING_FOR_MODEL", "WAITING_FOR_IDLE")
    val pendingSourceRequired: Boolean = deferred || status in setOf("RETRY", "FAILED")
    val wakeTrigger: String = when (status) {
        "WAITING_FOR_MODEL" -> "MODEL_AVAILABLE_OR_PROCESS_RESTART"
        "WAITING_FOR_IDLE" -> "RUNTIME_IDLE_OR_PROCESS_RESTART"
        "RETRY" -> "WORKMANAGER_BACKOFF"
        "FAILED" -> "RUNTIME_CHANGE_OR_EXPLICIT_RETRY"
        else -> "NONE"
    }
}
