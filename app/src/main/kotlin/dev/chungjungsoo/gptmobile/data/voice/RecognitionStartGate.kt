package dev.chungjungsoo.gptmobile.data.voice

/** Shared monotonic admission survives session and recognizer destruction. Never reset on navigation. */
internal class RecognitionStartGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var nextStart = 0L
    private var throttles = 0

    @Synchronized fun remainingMillis(): Long = (nextStart - now()).coerceAtLeast(0)

    @Synchronized fun acquire(): Boolean {
        if (remainingMillis() > 0) return false
        nextStart = now() + 500
        return true
    }

    @Synchronized fun succeeded() {
        throttles = 0
    }

    @Synchronized fun failed(tooManyRequests: Boolean) {
        val delay = if (tooManyRequests) {
            throttles++
            when (throttles) {
                1 -> 10_000L
                2 -> 30_000L
                else -> 60_000L
            }
        } else {
            2_000L
        }
        nextStart = maxOf(nextStart, now() + delay)
    }
}
