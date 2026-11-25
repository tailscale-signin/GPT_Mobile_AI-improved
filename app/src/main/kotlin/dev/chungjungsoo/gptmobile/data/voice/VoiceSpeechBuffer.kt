package dev.chungjungsoo.gptmobile.data.voice

/** Only stable text is spoken. Repeated snapshots add no queue entries. */
internal class VoiceSpeechBuffer(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val includeDetails: Boolean = false,
    private val preserveLiteralTerms: Boolean = false,
    private val skipPreamble: Boolean = false
) {
    constructor(clock: () -> Long) : this(clock, false)
    private var previous = ""
    private var previousSpeech = ""
    private var boundaryBase = 0
    private var boundaries = VoicePhraseBoundaries("")
    private var consumed = 0
    private var startedSpeech = false
    private var firstPendingAt: Long? = null
    fun append(text: String, final: Boolean, earlyStart: Boolean = false, followUpWords: Int = 12, feedback: VoiceSynthesisFeedback? = null, adaptive: Boolean = false): List<String>? {
        val end = minOf(text.length, 12000).let { if (it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it }
        val boundedText = text.substring(0, end)
        if (!final && boundedText == previous && !adaptive) return emptyList()
        val visible = if (!final && boundedText == previous) {
            previousSpeech
        } else {
            val projected = if (preserveLiteralTerms) {
                VoiceSpeechText.forPlayback(text, final = final, includeDetails = includeDetails)
            } else {
                VoiceSpeechText.project(text, final = final, includeDetails = includeDetails)
            }
            if (skipPreamble) VoiceSpokenLead.trim(projected, final) else projected
        }
        // Captions and unspoken words can be revised. Only the part already queued for
        // playback is irrevocable, including a final persistence trim of trailing space.
        val shared = minOf(consumed, visible.length)
        if (!previousSpeech.regionMatches(0, visible, 0, shared)) return null
        if (shared < consumed && (!final || previousSpeech.substring(shared, consumed).isNotBlank())) return null
        consumed = shared
        if (visible != previousSpeech || final) {
            // Committed groups cannot be revised. Parse only the remaining suffix on each token arrival.
            boundaryBase = consumed
            boundaries = VoicePhraseBoundaries(visible.substring(boundaryBase), streaming = !final)
        }
        previous = boundedText
        previousSpeech = visible
        val speechEnd = visible.length
        if (earlyStart) {
            val chunks = mutableListOf<String>()
            if (consumed < speechEnd && firstPendingAt == null) firstPendingAt = now()
            while (consumed < speechEnd) {
                val tail = visible.substring(consumed)
                val wordLimit = if (!startedSpeech) {
                    if (adaptive) VoiceAdaptivePlayback.OPENING_WORDS else 8
                } else if (adaptive) {
                    (feedback?.suggestedFollowUpWords ?: 12).coerceIn(6, 24)
                } else {
                    followUpWords
                }
                val characterLimit = if (adaptive) 128 else 240
                val words = completeWord.findAll(tail).take(wordLimit + 3).toList()
                val legalEnds = words.map { it.range.last + 1 }.filter { permitsBoundary(consumed + it) }
                val targetEnd = words.getOrNull(wordLimit - 1)?.range?.last?.plus(1)
                val wordEnd = targetEnd?.let { target -> legalEnds.lastOrNull { it <= target } ?: legalEnds.firstOrNull() }
                val clause = (if (startedSpeech && followUpWords > 12) phraseEnd else clauseEnd).findAll(tail)
                    .firstOrNull { boundary ->
                        permitsBoundary(consumed + boundary.range.last + 1) &&
                            (
                                startedSpeech ||
                                    boundary.value.any { it in ".!?" } ||
                                    completeWord.findAll(tail.substring(0, boundary.range.last + 1)).take(3).count() >= 3
                                )
                    }?.range?.last?.plus(1)
                val bounded = legalEnds.lastOrNull { it <= characterLimit }?.takeIf { tail.length >= characterLimit }
                val budgetMs = arrivalBudgetMs(feedback)
                val arrivalBoundary = legalEnds.lastOrNull { it <= (targetEnd ?: characterLimit) }?.takeIf {
                    adaptive && words.size >= 2 && now() - (firstPendingAt ?: now()) >= budgetMs
                }
                val naturalClause = clause?.takeIf { it <= 96 && (startedSpeech || !adaptive || completeWord.findAll(tail.substring(0, it)).take(wordLimit + 4).count() <= wordLimit + 3) }
                val boundary = naturalClause ?: listOfNotNull(clause, wordEnd, bounded, arrivalBoundary).minOrNull()?.takeIf { it <= characterLimit } ?: break
                val chunk = tail.substring(0, boundary)
                chunks += chunk
                if (chunk.isNotBlank()) startedSpeech = true
                consumed += boundary
                firstPendingAt = if (consumed < speechEnd) now() else null
            }
            if (final && consumed < speechEnd) {
                chunks += speechChunks(visible.substring(consumed))
                consumed = speechEnd
            }
            return chunks.filter(String::isNotBlank)
        }
        val stableEnd = if (final) {
            speechEnd
        } else {
            maxOf(
                consumed,
                sentenceEnd.findAll(visible, consumed).lastOrNull { permitsBoundary(it.range.last + 1) }?.range?.last?.plus(1) ?: 0
            )
        }
        val chunks = speechChunks(visible.substring(consumed, stableEnd)).filter(String::isNotBlank)
        consumed = stableEnd
        return chunks
    }

    fun nextFlushDelayMs(feedback: VoiceSynthesisFeedback?): Long? {
        val since = firstPendingAt ?: return null
        if (completeWord.findAll(previousSpeech, consumed).take(2).count() < 2) return null
        val delay = (arrivalBudgetMs(feedback) - (now() - since)).coerceAtLeast(0)
        if (delay == 0L && completeWord.findAll(previousSpeech, consumed).take(27).none { permitsBoundary(it.range.last + 1) }) return null
        return delay
    }

    private fun permitsBoundary(end: Int) = end >= boundaryBase && boundaries.permits(end - boundaryBase)

    private fun arrivalBudgetMs(feedback: VoiceSynthesisFeedback?): Long = when {
        !startedSpeech -> 120L
        (feedback?.bufferedAudioMs ?: 0) < 300 -> 200L
        else -> 500L
    }

    private companion object {
        val sentenceEnd = Regex("[.!?]\\s+")
        val clauseEnd = Regex("[.!?,;:]\\s+|\\n")
        val phraseEnd = Regex("[.!?;:]\\s+|\\n")
        val completeWord = Regex("\\S+\\s+")
    }
}

internal enum class VoicePauseDuration(val label: String, val maxQuietMs: Int?) {
    NATURAL("Natural", null),
    SHORT("Short", 140),
    MINIMAL("Minimal", 70)
}

internal enum class VoicePerformanceMode(val label: String, val followUpWords: Int, val bufferMs: Int, val refillWaitMs: Long) {
    AUTO("Auto", 16, 100, 60),
    QUALITY("Quality", 24, 180, 100),
    BALANCED("Balanced", 16, 100, 60),
    FAST("Fast", 12, 45, 0);

    val usesMeasuredPhraseLength: Boolean get() = this == AUTO || this == QUALITY
}
