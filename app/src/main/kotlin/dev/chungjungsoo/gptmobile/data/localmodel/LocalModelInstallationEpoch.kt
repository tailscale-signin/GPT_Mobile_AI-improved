package dev.chungjungsoo.gptmobile.data.localmodel

/** Invalidates known-absent package candidates only when installation state changes. */
internal object LocalModelInstallationEpoch {
    private val revision = java.util.concurrent.atomic.AtomicLong()
    fun current(): Long = revision.get()
    fun changed() {
        revision.incrementAndGet()
    }
}
