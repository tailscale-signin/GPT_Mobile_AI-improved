package dev.chungjungsoo.gptmobile.data.agent

/** Original rejection evidence and the current schedule for the same visible owner. */
data class ProviderWaitDeadline(
    val runId: String,
    val fence: Long,
    val retryAt: Long,
    val scheduledRetryAt: Long? = null
) {
    val effectiveRetryAt: Long get() = effectiveProviderRetryAt(retryAt, scheduledRetryAt)

    internal fun withSchedule(currentDeadline: Long?): ProviderWaitDeadline {
        val scheduled = effectiveProviderRetryAt(retryAt, currentDeadline).takeIf { it > retryAt }
        return if (scheduledRetryAt == scheduled) this else copy(scheduledRetryAt = scheduled)
    }
}

/** Scheduling can extend a receipt's deadline; it can never rewrite or shorten its evidence. */
internal fun effectiveProviderRetryAt(retryAt: Long, sharedDeadline: Long? = null, preflightDeadline: Long? = null): Long =
    maxOf(retryAt, sharedDeadline ?: 0L, preflightDeadline ?: 0L)
