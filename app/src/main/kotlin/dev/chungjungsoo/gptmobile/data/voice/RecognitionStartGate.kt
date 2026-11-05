package dev.chungjungsoo.gptmobile.data.voice

/** Shared monotonic admission survives session and recognizer destruction. Never reset on navigation. */
internal class RecognitionStartGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var nextStart = 0L
    private var throttles = 0
    private var bindingFailures = 0
    private var activeOwner: String? = null

    @Synchronized fun remainingMillis(): Long = maxOf((nextStart - now()).coerceAtLeast(0), if (activeOwner == null) 0L else 500L)

    @Synchronized fun acquire(owner: String = "default"): Boolean {
        if (remainingMillis() > 0) return false
        activeOwner = owner
        nextStart = now() + 500
        return true
    }

    @Synchronized fun release(owner: String = "default") {
        if (activeOwner == owner) activeOwner = null
    }

    @Synchronized fun succeeded(owner: String = "default") {
        release(owner)
        throttles = 0
        bindingFailures = 0
    }

    /** A service that rejected binding must not be hammered by auto-continue or navigation. */
    @Synchronized fun bindingFailed(owner: String = "default") {
        if (activeOwner != null && activeOwner != owner) return
        release(owner)
        bindingFailures = (bindingFailures + 1).coerceAtMost(3)
        val delay = when (bindingFailures) {
            1 -> 5_000L
            2 -> 15_000L
            else -> 30_000L
        }
        nextStart = maxOf(nextStart, now() + delay)
    }

    @Synchronized fun failed(tooManyRequests: Boolean, owner: String = "default") {
        if (activeOwner != null && activeOwner != owner) return
        release(owner)
        val delay = if (tooManyRequests) {
            throttles++
            when (throttles) {
                1 -> 1_000L
                2 -> 2_500L
                else -> 5_000L
            }
        } else {
            500L
        }
        nextStart = maxOf(nextStart, now() + delay)
    }
}
