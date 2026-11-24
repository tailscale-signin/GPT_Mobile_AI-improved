package dev.chungjungsoo.gptmobile.data.privacy

/** Per-channel state holds only a possible unfinished marker, never the whole answer. */
internal class PrivateStreamingRestorer(
    private val protocol: PrivateMarkerProtocol,
    private val restore: (String) -> PrivateProtectedText,
    private val assessAnswer: Boolean = true
) {
    private var pending = ""
    private var finished = false
    var maximumHeldCharacters: Int = 0
        private set
    var restorationNanos: Long = 0
        private set
    var restoredChunks: Int = 0
        private set
    var restoredRanges: Int = 0
        private set

    fun accept(delta: String): PrivateProtectedText {
        check(!finished) { "The private response channel has ended." }
        val combined = pending + delta
        val boundary = PrivateMarkerProtocol.incompleteSuffixStart(combined)
        pending = combined.substring(boundary)
        maximumHeldCharacters = maxOf(maximumHeldCharacters, pending.length)
        return restoreReady(combined.substring(0, boundary))
    }

    fun finish(): PrivateProtectedText {
        check(!finished) { "The private response channel has ended." }
        finished = true
        val tail = pending
        pending = ""
        return restoreReady(tail)
    }

    fun clear() {
        pending = ""
        finished = true
    }

    fun recordCompleteRestoration(result: PrivateProtectedText, elapsedNanos: Long) {
        check(result.receipt.confirmed && result.receipt.validFor(result.text)) { "Private restoration evidence is unavailable." }
        restorationNanos += elapsedNanos.coerceAtLeast(0)
        if (result.text.isNotEmpty()) {
            restoredChunks++
            restoredRanges += result.receipt.ranges.size
        }
    }

    private fun restoreReady(text: String): PrivateProtectedText {
        if (text.isEmpty()) return PrivateProtectedText("", PrivacyProtectionReceipt.create("", emptyList()))
        val prepared = protocol.prepare(text, assessAnswer)
        val started = System.nanoTime()
        return try {
            restore(prepared).also { recordCompleteRestoration(it, 0) }
        } finally {
            restorationNanos += (System.nanoTime() - started).coerceAtLeast(0)
        }
    }
}
