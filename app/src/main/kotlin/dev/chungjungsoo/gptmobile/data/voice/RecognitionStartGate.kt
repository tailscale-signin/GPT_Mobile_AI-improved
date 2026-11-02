package dev.chungjungsoo.gptmobile.data.voice

/** Monotonic admission survives recognizer destruction; stop/start cannot bypass cooldown. */
internal class RecognitionStartGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var nextStart = Long.MIN_VALUE

    fun acquire(): Boolean {
        val time = now()
        if (time < nextStart) return false
        nextStart = time + 500
        return true
    }

    fun failed(tooManyRequests: Boolean) {
        nextStart = maxOf(nextStart, now() + if (tooManyRequests) 10_000 else 2_000)
    }
}
