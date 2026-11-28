package dev.chungjungsoo.gptmobile.data.voice

/** Delivery timing only. The synchronized playback controller owns all calls. */
internal class VoiceStreamDeliveryCadence {
    var started = false
        private set
    private var active = false
    private var awaitingFirstIncrementalPcm = false
    private var deliveredAudio = false
    private var deliveryTailMs = 0L
    private var pendingMs = 0L
    private val completedTailsMs = ArrayDeque<Long>()

    fun begin(incrementalInput: Boolean = false) {
        check(!active)
        started = true
        active = true
        awaitingFirstIncrementalPcm = incrementalInput
        deliveredAudio = false
        deliveryTailMs = 0
    }

    /** The caller excludes callback work and bounded-queue waits from productionMs. */
    fun observe(productionMs: Long, retainedFrames: Int) {
        check(active)
        require(productionMs >= 0 && retainedFrames >= 0)
        // Opening a single reply-wide SDK stream happens once. Only intervals after
        // its first playable PCM can predict the next delivery. Phrase streams reopen.
        if (awaitingFirstIncrementalPcm) {
            if (retainedFrames > 0) awaitingFirstIncrementalPcm = false
            return
        }
        pendingMs += productionMs.coerceAtMost(Long.MAX_VALUE - pendingMs)
        if (retainedFrames > 0) {
            deliveredAudio = true
            deliveryTailMs = maxOf(deliveryTailMs, pendingMs)
            pendingMs = 0
        }
    }

    fun end(productionMs: Long) {
        observe(productionMs, 0)
        // Keep one maximum per actual stream. Instantaneous subdivisions of a native
        // result cannot evict a slow delivery; later completed streams still age it out.
        if (deliveredAudio) {
            completedTailsMs.addLast(deliveryTailMs)
            while (completedTailsMs.size > 8) completedTailsMs.removeFirst()
        }
        active = false
        deliveredAudio = false
        deliveryTailMs = 0
        // A trimmed tail and stream retirement still delay the next retained PCM.
        // Carry that producer time across the next begin, without callback waits.
    }

    fun reserveCostMs(): Long? = listOfNotNull(
        completedTailsMs.maxOrNull(),
        deliveryTailMs.takeIf { deliveredAudio },
        pendingMs.takeIf { it > 0 }
    ).maxOrNull()
}
