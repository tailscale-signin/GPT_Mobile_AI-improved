package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale
import kotlin.math.roundToLong

/** A confident native observation belongs only to its captured source, never to another draft. */
data class VoiceRecognitionLanguage(val languageTag: String, val text: String) {
    internal fun forSource(source: String, allowedTags: List<String>): String? {
        if (text != source || source.isBlank() || allowedTags.size != 2) return null
        fun language(tag: String): String? = runCatching {
            Locale.Builder().setLanguageTag(tag).build().language.takeIf { it.isNotBlank() && it != "und" }
        }.getOrNull()
        val observed = language(languageTag) ?: return null
        val pair = allowedTags.map { tag -> (language(tag) ?: return null) to tag }.toMap()
        return pair.takeIf { it.size == 2 }?.get(observed)
    }
}

/** Decoder emission positions are not acoustic word intervals or output-playback alignment. */
enum class VoiceRecognitionTimingProvenance {
    TOKEN_EMISSION,
    WORD_ALIGNMENT
}

enum class VoiceRecognitionTimingIssue {
    TEXT_LIMIT,
    TIMESTAMP_SHAPE,
    SOURCE_MISMATCH,
    INVALID_BOUNDARY
}

/** Character offsets are half-open UTF-16 offsets in the exact accompanying transcript. */
data class VoiceRecognitionTiming(
    val startChar: Int,
    val endCharExclusive: Int,
    val startFrame: Long,
    val endFrame: Long? = null,
    val provenance: VoiceRecognitionTimingProvenance = VoiceRecognitionTimingProvenance.TOKEN_EMISSION
) {
    init {
        require(startChar >= 0 && endCharExclusive > startChar && startFrame >= 0)
        require(endFrame == null || endFrame > startFrame)
        require(provenance != VoiceRecognitionTimingProvenance.TOKEN_EMISSION || endFrame == null)
    }

    val range: IntRange get() = startChar until endCharExclusive
}

/** A native word observation; a missing end remains unknown instead of borrowing the next onset. */
data class VoiceRecognitionWord(val text: String, val startSeconds: Double, val endSeconds: Double? = null)

/**
 * A revision belongs to one capture stream. Its timeline uses accepted real input frames, including
 * inter-segment silence; padding used only to flush a decoder is not captured audio. These timings
 * never replace the independent alignment of actual synthesized PCM used by output captions.
 */
data class VoiceRecognitionTranscript(
    val text: String,
    val streamId: Long,
    val revision: Long,
    val isFinal: Boolean,
    val sampleRate: Int,
    val audioFrames: Long,
    val timings: List<VoiceRecognitionTiming> = emptyList(),
    val timingIssue: VoiceRecognitionTimingIssue? = null
) {
    init {
        require(text.length <= MAX_CHARACTERS && streamId >= 0 && revision >= 0)
        require(sampleRate in 1..384_000 && audioFrames >= 0)
        require(timings.size <= MAX_TIMINGS && (timingIssue == null || timings.isEmpty()))
        require(validTimings(text, audioFrames, timings))
    }

    /** Only a supplied word end can establish an active interval. Token starts have no duration. */
    fun wordRangeAt(frame: Long): IntRange? = timings.firstOrNull {
        it.provenance == VoiceRecognitionTimingProvenance.WORD_ALIGNMENT &&
            it.endFrame != null &&
            frame >= it.startFrame &&
            frame < it.endFrame
    }?.range

    /** The latest emitted token is suitable for an arrival indicator, never for duration animation. */
    fun latestTokenRangeAt(frame: Long): IntRange? = timings.lastOrNull {
        it.provenance == VoiceRecognitionTimingProvenance.TOKEN_EMISSION && it.startFrame <= frame
    }?.range

    /** Trimming changes only source character offsets; it never resets the input audio clock. */
    fun trimmed(maxChars: Int = MAX_CHARACTERS): VoiceRecognitionTranscript {
        require(maxChars in 0..MAX_CHARACTERS)
        val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: text.length
        val trimmedEnd = text.indexOfLast { !it.isWhitespace() } + 1
        var end = minOf(maxOf(start, trimmedEnd), start + maxChars)
        if (!isCharacterBoundary(text, end)) end--
        val contained = timings.filter { it.startChar >= start && it.endCharExclusive <= end }.map {
            it.copy(startChar = it.startChar - start, endCharExclusive = it.endCharExclusive - start)
        }
        return copy(text = text.substring(start, end), timings = contained)
    }

    /** Arbitrary corrections invalidate timing. A spelling dictionary cannot relabel old spans. */
    fun withText(newText: String): VoiceRecognitionTranscript {
        if (newText == text) return this
        if (newText == text.trim()) return trimmed()
        var end = minOf(newText.length, MAX_CHARACTERS)
        if (!isCharacterBoundary(newText, end)) end--
        return copy(
            text = newText.substring(0, end),
            timings = emptyList(),
            timingIssue = if (newText.length > MAX_CHARACTERS) VoiceRecognitionTimingIssue.TEXT_LIMIT else VoiceRecognitionTimingIssue.SOURCE_MISMATCH
        )
    }

    companion object {
        const val MAX_CHARACTERS = 8_000
        const val MAX_TIMINGS = 8_000

        /**
         * Sherpa emits token times relative to its current segment. Preserve the native segment
         * offset and map only spellings that account for the complete source text. SentencePiece
         * word separators and WordPiece continuation markers are structural; unsupported byte
         * tokens or a text/token disagreement retain the transcript without invented timing.
         */
        fun fromTokens(
            text: String,
            tokens: List<String>,
            tokenStartSeconds: List<Double>,
            segmentStartSeconds: Double,
            streamId: Long,
            revision: Long,
            isFinal: Boolean,
            sampleRate: Int,
            audioFrames: Long
        ): VoiceRecognitionTranscript {
            val plain = VoiceRecognitionTranscript(boundedText(text), streamId, revision, isFinal, sampleRate, audioFrames)
            if (text.length > MAX_CHARACTERS) return plain.rejected(VoiceRecognitionTimingIssue.TEXT_LIMIT)
            if (tokens.isEmpty() && tokenStartSeconds.isEmpty()) return plain
            if (tokens.size != tokenStartSeconds.size || tokens.size > MAX_TIMINGS) {
                return plain.rejected(VoiceRecognitionTimingIssue.TIMESTAMP_SHAPE)
            }
            val frames = tokenStartSeconds.map {
                observedFrame(it, segmentStartSeconds, sampleRate, audioFrames)
                    ?: return plain.rejected(VoiceRecognitionTimingIssue.INVALID_BOUNDARY)
            }
            if (frames.zipWithNext().any { (before, after) -> before > after }) {
                return plain.rejected(VoiceRecognitionTimingIssue.INVALID_BOUNDARY)
            }
            var cursor = 0
            val spans = ArrayList<VoiceRecognitionTiming>(tokens.size)
            for ((index, token) in tokens.withIndex()) {
                if (token.isEmpty() || token.startsWith("<0x") || token.any { it == '\uFFFD' }) {
                    return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
                }
                val piece = token.replace('\u2581', ' ').removePrefix("##").trim()
                val beforeWhitespace = cursor
                while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                if (piece.isEmpty()) {
                    if (cursor == beforeWhitespace && (cursor != 0 || token != "\u2581")) {
                        return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
                    }
                    continue
                }
                if (!text.regionMatches(cursor, piece, 0, piece.length, ignoreCase = true)) {
                    return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
                }
                val end = cursor + piece.length
                if (!isCharacterBoundary(text, cursor) || !isCharacterBoundary(text, end)) {
                    return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
                }
                spans += VoiceRecognitionTiming(cursor, end, frames[index])
                cursor = end
            }
            if (text.substring(cursor).any { !it.isWhitespace() }) return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
            return plain.copy(timings = spans.toList())
        }

        /** Every lexical word must match in order. Only punctuation outside a word may be skipped. */
        fun fromWords(
            text: String,
            words: List<VoiceRecognitionWord>,
            segmentStartSeconds: Double,
            streamId: Long,
            revision: Long,
            isFinal: Boolean,
            sampleRate: Int,
            audioFrames: Long
        ): VoiceRecognitionTranscript {
            val plain = VoiceRecognitionTranscript(boundedText(text), streamId, revision, isFinal, sampleRate, audioFrames)
            if (text.length > MAX_CHARACTERS) return plain.rejected(VoiceRecognitionTimingIssue.TEXT_LIMIT)
            if (words.isEmpty()) return plain
            if (words.size > MAX_TIMINGS) return plain.rejected(VoiceRecognitionTimingIssue.TIMESTAMP_SHAPE)
            val expected = lexicalWords.findAll(text).toList()
            val recognized = words.map { word ->
                val matches = lexicalWords.findAll(word.text).toList()
                if (matches.size != 1) return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
                word to matches.single().value
            }
            if (expected.size != recognized.size) return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
            val spans = ArrayList<VoiceRecognitionTiming>(words.size)
            for (index in expected.indices) {
                val source = expected[index]
                val (word, spelling) = recognized[index]
                if (!source.value.equals(spelling, ignoreCase = true)) return plain.rejected(VoiceRecognitionTimingIssue.SOURCE_MISMATCH)
                val start = observedFrame(word.startSeconds, segmentStartSeconds, sampleRate, audioFrames)
                    ?: return plain.rejected(VoiceRecognitionTimingIssue.INVALID_BOUNDARY)
                val end = word.endSeconds?.let {
                    observedFrame(it, segmentStartSeconds, sampleRate, audioFrames)
                        ?: return plain.rejected(VoiceRecognitionTimingIssue.INVALID_BOUNDARY)
                }
                if (end != null && end <= start) return plain.rejected(VoiceRecognitionTimingIssue.INVALID_BOUNDARY)
                val previous = spans.lastOrNull()
                if (previous != null && start < (previous.endFrame ?: previous.startFrame)) {
                    return plain.rejected(VoiceRecognitionTimingIssue.INVALID_BOUNDARY)
                }
                spans += VoiceRecognitionTiming(source.range.first, source.range.last + 1, start, end, VoiceRecognitionTimingProvenance.WORD_ALIGNMENT)
            }
            return plain.copy(timings = spans.toList())
        }

        private val lexicalWords = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['\u2019][\\p{L}\\p{M}\\p{N}]+)*")

        private fun boundedText(text: String): String {
            var end = minOf(text.length, MAX_CHARACTERS)
            if (!isCharacterBoundary(text, end)) end--
            return text.substring(0, end)
        }

        private fun observedFrame(seconds: Double, offset: Double, sampleRate: Int, audioFrames: Long): Long? {
            if (!seconds.isFinite() || seconds < 0 || !offset.isFinite() || offset < 0) return null
            val frame = (seconds + offset) * sampleRate
            if (!frame.isFinite() || frame < 0 || frame > audioFrames.toDouble() + 0.5 || frame >= Long.MAX_VALUE.toDouble()) return null
            return frame.roundToLong().takeIf { it <= audioFrames }
        }

        private fun validTimings(text: String, audioFrames: Long, timings: List<VoiceRecognitionTiming>): Boolean {
            var previousChar = 0
            var previousFrame = 0L
            for (timing in timings) {
                if (timing.startChar < previousChar ||
                    timing.endCharExclusive > text.length ||
                    !isCharacterBoundary(text, timing.startChar) ||
                    !isCharacterBoundary(text, timing.endCharExclusive) ||
                    timing.startFrame < previousFrame ||
                    timing.startFrame > audioFrames ||
                    timing.endFrame?.let { it > audioFrames } == true
                ) {
                    return false
                }
                previousChar = timing.endCharExclusive
                previousFrame = timing.endFrame ?: timing.startFrame
            }
            return true
        }

        private fun isCharacterBoundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
            (offset == 0 || offset == text.length || !text[offset - 1].isHighSurrogate() || !text[offset].isLowSurrogate())
    }

    private fun rejected(issue: VoiceRecognitionTimingIssue): VoiceRecognitionTranscript = copy(timingIssue = issue)
}

/** A capture owns this ledger; stale callbacks cannot replace a newer revision or reopen a final. */
internal class VoiceRecognitionRevisions(private val streamId: Long) {
    @Volatile
    var current: VoiceRecognitionTranscript? = null
        private set

    init {
        require(streamId >= 0)
    }

    @Synchronized
    fun accept(candidate: VoiceRecognitionTranscript): VoiceRecognitionTranscript? {
        val previous = current
        if (candidate.streamId != streamId ||
            previous?.isFinal == true ||
            previous?.let { candidate.revision <= it.revision || candidate.sampleRate != it.sampleRate || candidate.audioFrames < it.audioFrames } == true
        ) {
            return null
        }
        val accepted = if (candidate.isFinal && candidate.text.isBlank() && previous?.text?.isNotBlank() == true) {
            candidate.copy(text = previous.text, timings = previous.timings, timingIssue = previous.timingIssue)
        } else {
            candidate.copy(timings = candidate.timings.toList())
        }
        current = accepted
        return accepted
    }
}
