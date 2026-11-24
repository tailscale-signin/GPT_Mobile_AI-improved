package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One observable owner token prevents a late completion from releasing another worker's audio. */
internal class VoiceSpeechBenchmarkOwnership {
    enum class QuarantineReason(val notice: String) {
        NATIVE_CLEANUP_UNCONFIRMED("Native speech cleanup could not be confirmed. Restart the app before starting more speech work.")
    }

    private val lock = Any()
    private val mutable = MutableStateFlow<Lease?>(null)
    val state = mutable.asStateFlow()
    private val quarantined = MutableStateFlow<QuarantineReason?>(null)
    val quarantineReason = quarantined.asStateFlow()

    fun acquire(): Lease? = synchronized(lock) {
        if (quarantined.value != null) return@synchronized null
        val lease = Lease()
        lease.takeIf { mutable.compareAndSet(null, it) }
    }

    inner class Lease internal constructor() {
        /** Executor retirement and successful native destruction are independent observations. */
        suspend fun releaseAfterRetirement(awaitRetired: suspend () -> Unit, cleanupFailed: () -> Boolean, retainFailedOwner: () -> Unit): Boolean {
            awaitRetired()
            if (cleanupFailed()) {
                quarantine()
                retainFailedOwner()
                return false
            }
            return release()
        }

        fun release(): Boolean = synchronized(lock) {
            quarantined.value == null && mutable.compareAndSet(this, null)
        }

        /** Only the still-current native owner can quarantine process-wide speech ownership. */
        fun quarantine(): Boolean = synchronized(lock) {
            if (mutable.value !== this) return@synchronized false
            quarantined.value = QuarantineReason.NATIVE_CLEANUP_UNCONFIRMED
            true
        }
    }
}
