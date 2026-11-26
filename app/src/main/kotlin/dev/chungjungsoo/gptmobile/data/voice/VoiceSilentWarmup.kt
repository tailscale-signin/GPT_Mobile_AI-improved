package dev.chungjungsoo.gptmobile.data.voice

/** Owns one optional, non-audible inference on the synthesis worker. */
internal class VoiceSilentWarmup {
    internal class Attempt internal constructor(
        internal val owner: Any,
        internal val speaker: Int,
        internal val rateBits: Int,
        internal val configurationId: String,
        internal val cancel: () -> Unit
    ) {
        internal var cancelled = false
    }

    private val lock = Any()
    private var current: Attempt? = null
    private var completed: Attempt? = null

    /** Reset and publication are atomic with cancellation; a live reply cannot lose its stop. */
    fun begin(owner: Any, speaker: Int, rate: Float, authorized: () -> Boolean, reset: () -> Unit, cancel: () -> Unit): Attempt? =
        begin(owner, speaker, rate, "", authorized, reset, cancel)

    fun begin(owner: Any, speaker: Int, rate: Float, configurationId: String, authorized: () -> Boolean, reset: () -> Unit, cancel: () -> Unit): Attempt? = synchronized(lock) {
        require(speaker >= 0 && rate.isFinite() && rate in .5f..2f)
        if (!authorized() || current != null) return@synchronized null
        if (completed?.let { it.owner === owner && it.speaker == speaker && it.rateBits == rate.toBits() && it.configurationId == configurationId } == true) return@synchronized null
        reset()
        Attempt(owner, speaker, rate.toBits(), configurationId, cancel).also { current = it }
    }

    fun isCurrent(attempt: Attempt, authorized: () -> Boolean): Boolean = synchronized(lock) {
        current === attempt && !attempt.cancelled && authorized()
    }

    /** The optional attempt argument binds a delayed deadline to its original native owner. */
    fun cancel(attempt: Attempt? = null): Boolean = synchronized(lock) {
        val running = current ?: return@synchronized false
        if (attempt != null && running !== attempt || running.cancelled) return@synchronized false
        running.cancelled = true
        // Do not release the lock between choosing an owner and signalling it: the
        // synthesis worker could otherwise reset that same engine for live speech.
        runCatching(running.cancel)
        true
    }

    fun finish(attempt: Attempt, succeeded: Boolean, authorized: () -> Boolean) = synchronized(lock) {
        if (current !== attempt) return@synchronized
        if (succeeded && !attempt.cancelled && authorized()) {
            // The completion key retains no JNI handle or cancellation closure.
            completed = Attempt(attempt.owner, attempt.speaker, attempt.rateBits, attempt.configurationId) {}
        }
        current = null
    }

    companion object {
        const val PHRASE = "the voice is ready."
        const val CANCELLATION_DEADLINE_MS = 5_000L
    }
}
