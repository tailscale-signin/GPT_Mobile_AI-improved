package dev.chungjungsoo.gptmobile.data.memory

/** Match WorkManager 2.12's initial battery rule; missing platform fields remain unknown evidence. */
internal fun initialEnrichmentBatteryNotLow(status: Int, level: Int, scale: Int): Boolean? = when {
    status == 1 -> true // BatteryManager.BATTERY_STATUS_UNKNOWN is treated as not low by WorkManager.
    status < 0 || level < 0 || scale <= 0 || level > scale -> null
    else -> level.toFloat() / scale > 0.15f
}

/** Scheduling evidence does not change WorkManager constraints or mark enrichment complete. */
internal data class MemoryEnrichmentScheduling(val outcome: String, val deferred: Boolean, val wakeTrigger: String, val constraintEvidence: String)

internal fun memoryEnrichmentScheduling(
    workerState: String,
    requiresBatteryNotLow: Boolean,
    batteryNotLow: Boolean?,
    reportedOutcome: String? = null,
    reportedWakeTrigger: String? = null,
    attempt: Int = 0
): MemoryEnrichmentScheduling {
    if (workerState == "ENQUEUED" && requiresBatteryNotLow && batteryNotLow == false) {
        return MemoryEnrichmentScheduling("WAITING_FOR_BATTERY", true, "BATTERY_OKAY", "SYSTEM_BATTERY_BROADCAST")
    }
    val outcome = reportedOutcome ?: "PENDING"
    val waiting = outcome.startsWith("WAITING_")
    val wake = reportedWakeTrigger ?: when {
        workerState == "ENQUEUED" && attempt > 0 -> "WORKMANAGER_BACKOFF_OR_CONSTRAINT"
        workerState == "ENQUEUED" -> "WORKMANAGER_SCHEDULE_OR_CONSTRAINT"
        workerState == "RUNNING" -> "IN_PROGRESS"
        else -> "NOT_RECORDED"
    }
    return MemoryEnrichmentScheduling(outcome, waiting, wake, if (batteryNotLow == null) "NOT_OBSERVED" else "SYSTEM_BATTERY_BROADCAST")
}
