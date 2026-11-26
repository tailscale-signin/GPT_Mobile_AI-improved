package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PrivacySettingsSaveState(val pending: Int = 0, val failed: Boolean = false) {
    val saving: Boolean get() = pending > 0
}

internal sealed interface PrivacySettingChange {
    val key: String

    data class Enabled(val enabled: Boolean) : PrivacySettingChange {
        override val key = "master"
    }

    data class VerifiedRetention(val required: Boolean) : PrivacySettingChange {
        override val key = "verified_retention"
    }

    data class Category(val category: PrivacyCategory, val protected: Boolean) : PrivacySettingChange {
        override val key = category.name
    }
}

/** One application-owned writer for both screens. Navigation never owns or cancels a save. */
internal class PrivacySettingsWriter(
    scope: CoroutineScope,
    private val persist: (PrivacySettingChange) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onResult: (PrivacySettingChange, Boolean) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private val pending = Channel<PrivacySettingChange>(Channel.UNLIMITED)
    private val failures = linkedMapOf<String, PrivacySettingChange>()
    private val mutable = MutableStateFlow(PrivacySettingsSaveState())
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            for (change in pending) {
                var succeeded = false
                try {
                    withContext(dispatcher) { persist(change) }
                    succeeded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last failed choice retryable, including a partially applied commit.
                } finally {
                    synchronized(lock) {
                        if (succeeded) failures.remove(change.key) else failures[change.key] = change
                        mutable.value = PrivacySettingsSaveState(mutable.value.pending - 1, failures.isNotEmpty())
                    }
                    // Optional diagnostics must never terminate the application-owned writer.
                    runCatching { onResult(change, succeeded) }
                }
            }
        }
    }

    fun submit(change: PrivacySettingChange) {
        synchronized(lock) {
            mutable.value = mutable.value.copy(pending = mutable.value.pending + 1)
            check(pending.trySend(change).isSuccess)
        }
    }

    fun retry() {
        synchronized(lock) {
            if (mutable.value.saving) return
            failures.values.toList().forEach(::submit)
        }
    }
}
