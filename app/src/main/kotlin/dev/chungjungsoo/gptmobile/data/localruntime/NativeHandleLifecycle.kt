package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A blocking native create may finish after cancellation. Never publish that handle. */
internal suspend fun adoptNativeHandle(create: () -> Long, validate: (Long) -> Unit, adopt: (Long) -> Unit, release: (Long) -> Unit) {
    val next = create()
    check(next != 0L) { "Native runtime failed to create a model handle" }
    try {
        currentCoroutineContext().ensureActive()
        validate(next)
        currentCoroutineContext().ensureActive()
        adopt(next)
    } catch (error: Throwable) {
        try {
            release(next)
        } catch (cleanup: Throwable) {
            if (cleanup !== error) error.addSuppressed(cleanup)
        }
        throw error
    }
}

/** The caller owns an idle handle; attempt destruction even when stop reports an error. */
internal fun releaseNativeHandle(handle: Long, stop: (Long) -> Unit, destroy: (Long) -> Unit) {
    var failure: Throwable? = null
    try {
        stop(handle)
    } catch (error: Throwable) {
        failure = error
    }
    try {
        destroy(handle)
    } catch (error: Throwable) {
        if (failure == null) throw error
        if (failure !== error) failure.addSuppressed(error)
    }
    failure?.let { throw it }
}
