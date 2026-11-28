package dev.chungjungsoo.gptmobile.data.voice

import android.content.Intent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal interface VoiceBenchRecognitionBinding {
    fun query(result: (RecognitionQualification) -> Unit)
    fun close()
}

internal fun voiceBenchRecognitionBinding(service: RecognitionService, intent: Intent): VoiceBenchRecognitionBinding {
    val recognizer = service.create(true)
    return object : VoiceBenchRecognitionBinding {
        override fun query(result: (RecognitionQualification) -> Unit) = service.checkSupport(recognizer, intent, true, result)
        override fun close() = recognizer.destroy()
    }
}

internal data class VoiceBenchRecognitionSupport(val qualification: RecognitionQualification?, val attempts: Int, val timedOutAttempts: Int) {
    val reason: String? get() = when {
        qualification == null -> "CAPABILITY_QUERY_TIMEOUT"
        qualification.status == RecognitionSupportStatus.SUPPORTED -> null
        qualification.errorCode != null -> "${qualification.status.name}_ANDROID_ERROR_${qualification.errorCode}"
        else -> qualification.status.name
    }
    val metrics: Map<String, Double> get() = buildMap {
        put("capabilityAttempts", attempts.toDouble())
        put("capabilityTimeouts", timedOutAttempts.toDouble())
        qualification?.errorCode?.let { put("androidErrorCode", it.toDouble()) }
    }
}

/** A fresh local binding gets one bounded recovery attempt. Late replies cannot qualify its successor. */
internal suspend fun voiceBenchRecognitionSupport(
    active: () -> Boolean,
    interrupt: AtomicReference<(() -> Unit)?>,
    timeoutMs: Long = 2_500L,
    create: () -> VoiceBenchRecognitionBinding
): VoiceBenchRecognitionSupport {
    require(timeoutMs > 0)
    for (attempt in 1..2) {
        if (!active()) throw CancellationException("Voice benchmark stopped")
        val binding = create()
        val pending = CompletableDeferred<RecognitionQualification>()
        val stop = {
            pending.cancel()
            Unit
        }
        interrupt.set(stop)
        val qualification = try {
            if (!active()) throw CancellationException("Voice benchmark stopped")
            binding.query { pending.complete(it) }
            withTimeoutOrNull(timeoutMs) { pending.await() }
        } finally {
            pending.cancel()
            interrupt.compareAndSet(stop, null)
            binding.close()
        }
        if (qualification != null) return VoiceBenchRecognitionSupport(qualification, attempt, attempt - 1)
        if (attempt == 1) delay(150L)
    }
    return VoiceBenchRecognitionSupport(null, 2, 2)
}
