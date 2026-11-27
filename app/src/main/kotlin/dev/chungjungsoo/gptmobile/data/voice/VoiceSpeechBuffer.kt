package dev.chungjungsoo.gptmobile.data.voice

/** Only stable text is spoken. Repeated snapshots add no queue entries. */
internal class VoiceSpeechBuffer(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val includeDetails: Boolean = false,
    private val preserveLiteralTerms: Boolean = false,
    private val skipPreamble: Boolean = false,
    private val dictionary: VoicePronunciationDictionary = VoicePronunciationDictionary.EMPTY
) {
    constructor(clock: () -> Long) : this(clock, false)
    var heardOpening: String? = null
        set(value) {
            if (field != value) previous = ""
            field = value
        }
    fun spokenLead(text: String, final: Boolean): String {
        val distinct = heardOpening?.let { VoiceSpokenLead.afterCue(text, it, final) } ?: text
        return if (skipPreamble) VoiceSpokenLead.trim(distinct, final) else distinct
    }
    private var adaptiveTail = false
    private var lastTextChange = 0L
    private var previous = ""
    private var previousSpeech = ""
    private var finalText = false
    private var boundaryBase = 0
    private var boundaries = VoicePhraseBoundaries("")
    private var consumed = 0
    private var startedSpeech = false
    private var firstPendingAt: Long? = null
    fun append(text: String, final: Boolean, earlyStart: Boolean = false, followUpWords: Int = 12, feedback: VoiceSynthesisFeedback? = null, adaptive: Boolean = false): List<String>? {
        val end = minOf(text.length, 12000).let { if (it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it }
        val boundedText = text.substring(0, end)
        adaptiveTail = adaptive
        if (!final && boundedText == previous && !adaptive && (!earlyStart || startedSpeech)) return emptyList()
        val visible = if (!final && boundedText == previous) {
            previousSpeech
        } else {
            val projected = if (preserveLiteralTerms) {
                VoiceSpeechText.forPlayback(text, final = final, includeDetails = includeDetails)
            } else {
                VoiceSpeechText.project(text, final = final, includeDetails = includeDetails)
            }
            spokenLead(projected, final)
        }
        // Captions and unspoken words can be revised. Only the part already queued for
        // playback is irrevocable, including a final persistence trim of trailing space.
        val shared = minOf(consumed, visible.length)
        if (!previousSpeech.regionMatches(0, visible, 0, shared)) return null
        if (shared < consumed && (!final || previousSpeech.substring(shared, consumed).isNotBlank())) return null
        consumed = shared
        if (!startedSpeech && boundedText != previous) lastTextChange = now()
        if (visible != previousSpeech || final) {
            // Committed groups cannot be revised. Parse only the remaining suffix on each token arrival.
            boundaryBase = consumed
            boundaries = VoicePhraseBoundaries(visible.substring(boundaryBase), streaming = !final)
        }
        finalText = final
        previous = boundedText
        previousSpeech = visible
        val speechEnd = visible.length
        if (earlyStart) {
            val chunks = mutableListOf<String>()
            if (consumed < speechEnd && firstPendingAt == null) firstPendingAt = now()
            while (consumed < speechEnd) {
                val wordLimit = if (!startedSpeech) {
                    if (adaptive) VoiceAdaptivePlayback.OPENING_WORDS else 8
                } else if (adaptive) {
                    (feedback?.suggestedFollowUpWords ?: 12).coerceIn(6, 24)
                } else {
                    followUpWords
                }
                val characterLimit = if (adaptive) ADAPTIVE_CHARACTERS else 240
                // One character beyond the limit distinguishes a complete separator from one
                // truncated by this window. A boundary outside the limit cannot be emitted.
                val tail = visible.substring(consumed, minOf(speechEnd, consumed + characterLimit + 1))
                val words = completeWord.findAll(tail).take(wordLimit + 3).toList()
                val legalEnds = words.map { it.range.last + 1 }.filter { permitsBoundary(consumed + it) }
                val targetEnd = words.getOrNull(wordLimit - 1)?.range?.last?.plus(1)
                val wordEnd = targetEnd?.let { target -> legalEnds.lastOrNull { it <= target } ?: legalEnds.firstOrNull() }
                val clause = (if (startedSpeech && followUpWords > 12) phraseEnd else clauseEnd).findAll(tail)
                    .firstOrNull { boundary ->
                        permitsBoundary(consumed + boundary.range.last + 1) &&
                            (
                                startedSpeech ||
                                    VoicePhraseBoundaries.endsSentence(tail, boundary.range.first) ||
                                    completeWord.findAll(tail.substring(0, boundary.range.last + 1)).take(3).count() >= 3
                                )
                    }?.range?.last?.plus(1)
                val bounded = legalEnds.lastOrNull { it <= characterLimit }?.takeIf { tail.length >= characterLimit }
                val budgetMs = arrivalBudgetMs(feedback)
                val arrivalBoundary = legalEnds.lastOrNull { it <= (targetEnd ?: characterLimit) }?.takeIf {
                    (adaptive || !startedSpeech) && words.size >= 2 && now() - (firstPendingAt ?: now()) >= budgetMs
                }
                val naturalClause = clause?.takeIf { it <= 96 && (startedSpeech || !adaptive || completeWord.findAll(tail.substring(0, it)).take(wordLimit + 4).count() <= wordLimit + 3) }
                val settledSentence = if (openingPunctuationDelayMs() == 0L) speechEnd - consumed else null
                val boundary = naturalClause ?: settledSentence ?: listOfNotNull(clause, wordEnd, bounded, arrivalBoundary).minOrNull()?.takeIf { it <= characterLimit } ?: break
                val chunk = tail.substring(0, boundary)
                chunks += chunk
                if (chunk.isNotBlank()) startedSpeech = true
                consumed += boundary
                firstPendingAt = if (consumed < speechEnd) now() else null
            }
            if (final && consumed < speechEnd) {
                chunks += speechChunks(visible.substring(consumed), dictionary = dictionary)
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
        val chunks = speechChunks(visible.substring(consumed, stableEnd), dictionary = dictionary).filter(String::isNotBlank)
        consumed = stableEnd
        return chunks
    }

    fun nextFlushDelayMs(feedback: VoiceSynthesisFeedback?): Long? {
        if (startedSpeech && !adaptiveTail) return null
        val since = firstPendingAt ?: return null
        val tail = previousSpeech.substring(consumed, minOf(previousSpeech.length, consumed + ADAPTIVE_CHARACTERS + 1))
        val ends = completeWord.findAll(tail).take(27).map { it.range.last + 1 }.toList()
        val punctuationDelay = openingPunctuationDelayMs()
        if (ends.size < 2 || ends.none { it <= ADAPTIVE_CHARACTERS && permitsBoundary(consumed + it) }) return punctuationDelay
        val wordDelay = (arrivalBudgetMs(feedback) - (now() - since)).coerceAtLeast(0)
        return punctuationDelay?.let { minOf(it, wordDelay) } ?: wordDelay
    }

    // A provider can pause after a short question without sending the next word or separator.
    // Allow a closing quote or another punctuation token to arrive before committing the first sentence.
    private fun openingPunctuationDelayMs(): Long? {
        if (startedSpeech || previousSpeech.length - consumed !in 1..ADAPTIVE_CHARACTERS) return null
        if (previous.lastOrNull() != previousSpeech.lastOrNull() || !strongSentenceEnd.containsMatchIn(previousSpeech) || !permitsBoundary(previousSpeech.length) || previousSpeech.substring(consumed).none(Char::isLetterOrDigit)) return null
        return (80L - (now() - lastTextChange)).coerceAtLeast(0)
    }

    private fun permitsBoundary(end: Int) = end >= boundaryBase && boundaries.permits(end - boundaryBase) && dictionary.permitsSplit(previousSpeech, end, finalText)

    private fun arrivalBudgetMs(feedback: VoiceSynthesisFeedback?): Long {
        if (!startedSpeech) return 120L
        // When audio is already queued, give the provider time to finish the next
        // thought. As the playback head catches up, release available words sooner.
        return ((feedback?.bufferedAudioMs ?: 0L) - 300L).coerceIn(200L, 1000L)
    }

    private companion object {
        const val ADAPTIVE_CHARACTERS = 128
        const val QUOTE_AND_SPACE = """["'”’»)}\]]*\s+"""
        val strongSentenceEnd = Regex("""[!?]+["'”’»)}\]]*$""")
        val sentenceEnd = Regex("[.!?…]$QUOTE_AND_SPACE|[。！？।॥]\\s*")
        val clauseEnd = Regex("[.!?…,;:]$QUOTE_AND_SPACE|[。！？।॥]\\s*|\\n")
        val phraseEnd = Regex("[.!?…;:]$QUOTE_AND_SPACE|[。！？।॥]\\s*|\\n")
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
