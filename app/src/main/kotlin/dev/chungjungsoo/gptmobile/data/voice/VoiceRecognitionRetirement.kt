package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.atomic.AtomicBoolean

/** Uncertain native destruction cannot be repaired by constructing another screen or model store. */
internal class VoiceRecognitionRetirement {
    private val failed = AtomicBoolean()
    val blocked: Boolean get() = failed.get()
    fun block() {
        failed.set(true)
    }

    companion object {
        // Production owners share this quarantine for the lifetime of the process.
        val process = VoiceRecognitionRetirement()
    }
}
