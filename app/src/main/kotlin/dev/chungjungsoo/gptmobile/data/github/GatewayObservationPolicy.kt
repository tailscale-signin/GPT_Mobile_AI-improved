package dev.chungjungsoo.gptmobile.data.github

/** Phone work observes durable server state; it never takes ownership of inference. */
internal object GatewayObservationPolicy {
    const val SLICE_MILLIS = 45_000L
    const val MAX_IMMEDIATE_RETRIES = 3

    enum class Next { OBSERVED, RETRY, KEEP_CHECKPOINT }

    fun next(synchronized: Boolean, runAttemptCount: Int): Next = when {
        synchronized -> Next.OBSERVED
        runAttemptCount < MAX_IMMEDIATE_RETRIES -> Next.RETRY
        else -> Next.KEEP_CHECKPOINT
    }
}
