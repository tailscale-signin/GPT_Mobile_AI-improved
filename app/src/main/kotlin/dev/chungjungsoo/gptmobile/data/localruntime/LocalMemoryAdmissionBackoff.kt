package dev.chungjungsoo.gptmobile.data.localruntime

/** Avoid inspecting/loading an unchanged plan repeatedly while its memory deficit persists. */
internal class LocalMemoryAdmissionBackoff(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var blocked: Triple<String, LocalMemoryEstimate, Long>? = null

    fun remember(identity: String, estimate: LocalMemoryEstimate) {
        blocked = Triple(identity, estimate, now())
    }

    fun clear() {
        blocked = null
    }

    fun rejection(identity: String, availableBytes: Long?): LocalMemoryAdmissionException? {
        val (previousIdentity, previous, observedAt) = blocked ?: return null
        if (identity != previousIdentity || now() - observedAt !in 0 until 30_000L) {
            clear()
            return null
        }
        // Unknown headroom warrants a new measurement, never a stale rejection.
        if (availableBytes == null) return null
        val fresh = previous.copy(
            availableBytes = availableBytes,
            systemMemory = null,
            memoryPlan = previous.memoryPlan?.copy(availableBytes = availableBytes)
        )
        if (!fresh.budget.hasShortfall) {
            clear()
            return null
        }
        val remainingSeconds = (30_000L - (now() - observedAt) + 999) / 1000
        return fresh.rejection(
            "The unchanged plan is paused for ${remainingSeconds.coerceAtLeast(1)}s after memory rejection. " +
                "It resumes immediately when sufficient memory is available or the model settings change."
        )
    }
}
