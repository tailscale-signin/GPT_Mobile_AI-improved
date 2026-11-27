package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

/** A provisional end covers the observed PCM prefix of a word that is still arriving. */
internal data class VoiceStreamCue(val range: IntRange, val startFrame: Long, val endFrame: Long, val provenance: VoiceWordCueProvenance, val finalEnd: Boolean = true)

internal const val VOICE_CUE_PUNCTUATION = ".,;:!?。，、；：！？"

/** A Tasks word timestamp can include the punctuation closing a clause. Keep symbols and internal punctuation. */
internal fun VoiceStreamCue.lexicalRange(source: String): VoiceStreamCue {
    if (range.isEmpty()) return this
    var last = range.last
    while (last > range.first && source[last] in VOICE_CUE_PUNCTUATION) last--
    return if (last == range.last || source.slice(range.first..last).none(Char::isLetterOrDigit)) {
        this
    } else {
        copy(range = range.first..last)
    }
}

/** Never close a producer from a playback callback; its synthesis owner retires it after this returns. */
internal fun consumeVoiceStream(engine: VoicePhraseEngine, text: String, speaker: Int, speed: Float, active: () -> Boolean, emit: (VoicePcmChunk) -> Boolean) {
    try {
        if (!active()) throw CancellationException("Speech stream cancelled")
        engine.stream(text, speaker, speed) { chunk ->
            if (!active() || !emit(chunk)) throw CancellationException("Speech stream cancelled")
            true
        }
        if (!active()) throw CancellationException("Speech stream cancelled")
    } catch (failure: Throwable) {
        runCatching { engine.cancel() }
        throw failure
    }
}

internal fun consumeVoiceInputStream(engine: VoicePhraseEngine, nextText: () -> String?, speaker: Int, speed: Float, active: () -> Boolean, emit: (VoicePcmChunk) -> Boolean) {
    try {
        if (!active()) throw CancellationException("Speech stream cancelled")
        engine.streamInput({
            if (!active()) throw CancellationException("Speech stream cancelled")
            nextText().also { if (!active()) throw CancellationException("Speech stream cancelled") }
        }, speaker, speed) { chunk ->
            if (!active() || !emit(chunk)) throw CancellationException("Speech stream cancelled")
            true
        }
        if (!active()) throw CancellationException("Speech stream cancelled")
    } catch (failure: Throwable) {
        runCatching { engine.cancel() }
        throw failure
    }
}

/** Validates bounded streamed PCM and maps byte-based SDK cues without retaining audio. */
internal class VoiceStreamFrames(
    private val text: String,
    private val sourceRanges: List<IntRange>? = null,
    private val sourceCharacters: List<IntRange>? = null,
    private val incrementalText: VoiceIncrementalTextMap? = null,
    val frameLimit: Long = MAX_STREAM_FRAMES,
    private val cueLimit: Int = MAX_CUES
) {
    private val byteBoundaries = utf8Boundaries(text)
    private val words = Regex("\\S+").findAll(text).map { it.range }.toList()
    private var lastCueFrame = 0L
    private var lastCueByte = 0
    private var openCue: VoiceWordCue? = null
    private var maximumCueEnd = 0L
    private var cues = 0
    private var nonSilent = false
    var frames = 0L
        private set

    init {
        require(sourceRanges == null || sourceRanges.size == words.size)
        require(sourceCharacters == null || sourceCharacters.size == text.length)
        require(sourceRanges == null || sourceCharacters == null)
        require(incrementalText == null || text.isEmpty() && sourceRanges == null && sourceCharacters == null)
        require(frameLimit in 1..VoiceIncrementalStreamLimits.MAX_FRAMES && cueLimit in 1..VoiceIncrementalStreamLimits.MAX_CUES)
    }

    fun accept(chunk: VoicePcmChunk): List<VoiceStreamCue> {
        require(chunk.sampleRate == VOICE_PCM_SAMPLE_RATE) { "STREAM_SAMPLE_RATE" }
        require(chunk.samples.size <= MAX_CHUNK_FRAMES && frames + chunk.samples.size <= frameLimit) { "STREAM_FRAME_LIMIT" }
        require(chunk.samples.all(Float::isFinite)) { "STREAM_PCM_INVALID" }
        nonSilent = nonSilent || chunk.samples.any { kotlin.math.abs(it) >= 1e-5f }
        require(chunk.wordCues.size <= cueLimit + 1) { "STREAM_CUE_LIMIT" }
        val mapped = chunk.wordCues.map { cue ->
            val previous = openCue
            if (previous == null) {
                require(++cues <= cueLimit) { "STREAM_CUE_LIMIT" }
                require(cue.startByte >= lastCueByte && cue.endByte > cue.startByte) { "STREAM_CUE_ORDER" }
                require(cue.startFrame >= lastCueFrame) { "STREAM_CUE_FRAME_RANGE" }
            } else {
                require(
                    cue.startByte == previous.startByte &&
                        cue.endByte == previous.endByte &&
                        cue.startFrame == previous.startFrame &&
                        cue.endFrame >= previous.endFrame &&
                        cue.provenance == previous.provenance
                ) { "STREAM_CUE_REVISION" }
            }
            require(cue.endFrame > cue.startFrame && cue.endFrame <= frameLimit) { "STREAM_CUE_FRAME_RANGE" }
            if (previous != null || !cue.finalEnd) require(cue.endFrame <= frames + chunk.samples.size) { "STREAM_CUE_UNOBSERVED" }
            val range = if (incrementalText != null) {
                incrementalText.range(cue.startByte, cue.endByte)
            } else {
                val start = byteBoundaries.getOrNull(cue.startByte)?.takeIf { it >= 0 } ?: error("STREAM_CUE_BYTE_BOUNDARY")
                val end = byteBoundaries.getOrNull(cue.endByte)?.takeIf { it >= 0 } ?: error("STREAM_CUE_BYTE_BOUNDARY")
                if (sourceCharacters != null) {
                    val selected = sourceCharacters.subList(start, end).filterNot(IntRange::isEmpty)
                    if (selected.isEmpty()) IntRange.EMPTY else selected.minOf { it.first }..selected.maxOf { it.last }
                } else if (sourceRanges == null) {
                    start until end
                } else {
                    val selected = words.indices.filter { words[it].first < end && words[it].last >= start }.map { sourceRanges[it] }.filterNot(IntRange::isEmpty)
                    if (selected.isEmpty()) IntRange.EMPTY else selected.minOf { it.first }..selected.maxOf { it.last }
                }
            }
            lastCueByte = cue.startByte
            lastCueFrame = cue.endFrame
            maximumCueEnd = maxOf(maximumCueEnd, cue.endFrame)
            openCue = cue.takeUnless { it.finalEnd }
            VoiceStreamCue(range, cue.startFrame, cue.endFrame, cue.provenance, cue.finalEnd)
        }
        frames += chunk.samples.size
        return mapped
    }

    fun finish() {
        require(frames > 0 && maximumCueEnd <= frames && openCue == null) { "STREAM_INCOMPLETE" }
        require(nonSilent) { "STREAM_SILENT" }
    }

    companion object {
        fun incremental(text: VoiceIncrementalTextMap): VoiceStreamFrames = VoiceStreamFrames(
            "",
            incrementalText = text,
            frameLimit = VoiceIncrementalStreamLimits.MAX_FRAMES,
            cueLimit = VoiceIncrementalStreamLimits.MAX_CUES
        )

        const val MAX_CHUNK_FRAMES = VOICE_PCM_SAMPLE_RATE * 6
        const val MAX_STREAM_FRAMES = VOICE_PCM_SAMPLE_RATE * 30L
        const val MAX_CUES = 2_048

        /** Interior UTF-8 bytes remain -1; surrogate pairs map only at code point boundaries. */
        internal fun utf8Boundaries(text: String): IntArray {
            val result = IntArray(text.toByteArray(Charsets.UTF_8).size + 1) { -1 }
            var utf16 = 0
            var bytes = 0
            result[0] = 0
            while (utf16 < text.length) {
                val codePoint = text.codePointAt(utf16)
                require(codePoint !in 0xD800..0xDFFF) { "STREAM_TEXT_INVALID" }
                bytes += when {
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    codePoint <= 0xFFFF -> 3
                    else -> 4
                }
                utf16 += Character.charCount(codePoint)
                result[bytes] = utf16
            }
            return result
        }
    }
}
