package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A failed optional observer must not permanently disable preparation for this process. */
internal fun CoroutineScope.launchReadinessObserver(name: String, onFailure: () -> Unit = {}, observe: suspend () -> Unit) = launch {
    while (isActive) {
        try {
            observe()
            return@launch
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure()
            val frame = failure.stackTrace.firstOrNull { it.className.startsWith("dev.chungjungsoo.gptmobile") }
            AppLogRecorder.record("Readiness", "OBSERVER_RESTART · observer=$name · cause=${failure.javaClass.simpleName} · location=${frame?.className}:${frame?.lineNumber} · retryMillis=5000", "W")
            delay(5_000)
        }
    }
}
