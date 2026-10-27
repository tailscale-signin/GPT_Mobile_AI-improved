package dev.chungjungsoo.gptmobile.data.localruntime

/**
 * A completed coroutine is not proof that its blocking native call released
 * the operation lease. Only terminate for the same request that timed out.
 */
internal object NativeCancellationWatchdog {
    fun shouldTerminate(
        terminating: Boolean,
        requestId: String,
        activeRequestId: String?,
        jobCompleted: Boolean?
    ): Boolean =
        !terminating &&
            (activeRequestId == requestId || jobCompleted == false)
}
