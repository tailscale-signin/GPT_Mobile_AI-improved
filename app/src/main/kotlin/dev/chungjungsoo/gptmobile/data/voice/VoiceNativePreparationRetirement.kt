package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.atomic.AtomicReference

/** A rejected preparation still owns its opened handle until destruction succeeds. */
internal class VoiceNativePreparationRetirement {
    private val unretired = AtomicReference<AutoCloseable?>(null)
    val retainedOwner: AutoCloseable? get() = unretired.get()
    val nativeCleanupPending: Boolean get() = retainedOwner != null

    fun <T> transfer(owner: AutoCloseable, prepare: () -> T): T {
        try {
            return prepare()
        } catch (failure: Throwable) {
            try {
                owner.close()
            } catch (closeFailure: Throwable) {
                unretired.compareAndSet(null, owner)
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            // Keep cancellation and native/runtime failures in their original categories.
            // The factory/runner owns the separate failed-destruction observation.
            throw failure
        }
    }
}
