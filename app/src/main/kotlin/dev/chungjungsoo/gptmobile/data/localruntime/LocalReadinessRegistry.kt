package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalArtifactIncompatibleException :
    IllegalStateException(
        "ENGINE_INITIALIZATION: This artifact's tensor contract is incompatible with the selected runtime/backend. Replace the export or run explicit compatibility verification after changing the installation."
    )

/** Only deterministic artifact errors are retained. Resource pressure and cancellation stay retryable. */
internal class LocalReadinessRegistry {
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val lock = Mutex()

    suspend fun <T> qualify(key: String, load: suspend () -> T): T = lock.withLock {
        if (key in blocked) throw LocalArtifactIncompatibleException()
        try {
            load()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (deterministicFailure(error)) {
                blocked += key
                throw LocalArtifactIncompatibleException()
            }
            throw error
        }
    }

    fun reset() = blocked.clear()

    companion object {
        fun deterministicFailure(error: Throwable): Boolean = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
            .take(8).any { it.message.orEmpty().contains("Input tensor not found", true) || it is LocalArtifactIncompatibleException }
    }
}
