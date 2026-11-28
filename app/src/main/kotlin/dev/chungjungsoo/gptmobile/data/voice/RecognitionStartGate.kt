package dev.chungjungsoo.gptmobile.data.voice

/** Shared monotonic admission survives session and recognizer destruction. Never reset on navigation. */
internal class RecognitionStartGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class BlockReason { ACTIVE_CAPTURE, REQUEST_SPACING, RATE_LIMITED, SERVICE_UNAVAILABLE }
    data class Block(val reason: BlockReason, val remainingMillis: Long)

    private var nextStart = 0L
    private var cooldownReason = BlockReason.REQUEST_SPACING
    private var throttles = 0
    private var bindingFailures = 0
    private var activeOwner: String? = null

    @Synchronized fun remainingMillis(): Long = maxOf((nextStart - now()).coerceAtLeast(0), if (activeOwner == null) 0L else 500L)

    /** Retain the original cooldown category across screen changes and denied taps. */
    @Synchronized fun blocked(): Block? {
        val remaining = remainingMillis()
        if (remaining == 0L) return null
        return Block(if (activeOwner != null) BlockReason.ACTIVE_CAPTURE else cooldownReason, remaining)
    }

    @Synchronized fun acquire(owner: String = "default"): Boolean {
        if (remainingMillis() > 0) return false
        activeOwner = owner
        nextStart = now() + 500
        cooldownReason = BlockReason.REQUEST_SPACING
        return true
    }

    @Synchronized fun release(owner: String = "default") {
        if (activeOwner == owner) activeOwner = null
    }

    @Synchronized fun succeeded(owner: String = "default") {
        if (activeOwner != owner) return
        release(owner)
        throttles = 0
        bindingFailures = 0
    }

    /** A service that rejected binding must not be hammered by auto-continue or navigation. */
    @Synchronized fun bindingFailed(owner: String = "default") {
        if (activeOwner != owner) return
        release(owner)
        bindingFailures = (bindingFailures + 1).coerceAtMost(3)
        val delay = when (bindingFailures) {
            1 -> 5_000L
            2 -> 15_000L
            else -> 30_000L
        }
        nextStart = maxOf(nextStart, now() + delay)
        cooldownReason = BlockReason.SERVICE_UNAVAILABLE
    }

    @Synchronized fun failed(tooManyRequests: Boolean, owner: String = "default") {
        if (activeOwner != owner) return
        release(owner)
        val delay = if (tooManyRequests) {
            // Application retry pacing, not a claimed Android quota/reset time.
            // Keep it across recognizer recreation and require explicit retry in the caller.
            throttles = (throttles + 1).coerceAtMost(3)
            when (throttles) {
                1 -> 15_000L
                2 -> 30_000L
                else -> 60_000L
            }
        } else {
            500L
        }
        nextStart = maxOf(nextStart, now() + delay)
        cooldownReason = if (tooManyRequests) BlockReason.RATE_LIMITED else BlockReason.REQUEST_SPACING
    }
}
