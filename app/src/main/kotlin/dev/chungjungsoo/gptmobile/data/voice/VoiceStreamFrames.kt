package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

internal data class VoiceStreamCue(val range: IntRange, val startFrame: Long, val endFrame: Long, val provenance: VoiceWordCueProvenance)

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

/** Validates bounded streamed PCM and maps byte-based SDK cues without retaining audio. */
internal class VoiceStreamFrames(private val text: String, private val sourceRanges: List<IntRange>? = null, private val sourceCharacters: List<IntRange>? = null) {
    private val byteBoundaries = utf8Boundaries(text)
    private val words = Regex("\\S+").findAll(text).map { it.range }.toList()
    private var lastCueFrame = 0L
    private var lastCueByte = 0
    private var maximumCueEnd = 0L
    private var cues = 0
    private var nonSilent = false
    var frames = 0L
        private set

    init {
        require(sourceRanges == null || sourceRanges.size == words.size)
        require(sourceCharacters == null || sourceCharacters.size == text.length)
        require(sourceRanges == null || sourceCharacters == null)
    }

    fun accept(chunk: VoicePcmChunk): List<VoiceStreamCue> {
        require(chunk.sampleRate == VOICE_PCM_SAMPLE_RATE) { "STREAM_SAMPLE_RATE" }
        require(chunk.samples.size <= MAX_CHUNK_FRAMES && frames + chunk.samples.size <= MAX_STREAM_FRAMES) { "STREAM_FRAME_LIMIT" }
        require(chunk.samples.all(Float::isFinite)) { "STREAM_PCM_INVALID" }
        nonSilent = nonSilent || chunk.samples.any { kotlin.math.abs(it) >= 1e-5f }
        require(chunk.wordCues.size <= MAX_CUES - cues) { "STREAM_CUE_LIMIT" }
        val mapped = chunk.wordCues.map { cue ->
            require(cue.startByte >= lastCueByte && cue.endByte > cue.startByte) { "STREAM_CUE_ORDER" }
            val start = byteBoundaries.getOrNull(cue.startByte)?.takeIf { it >= 0 } ?: error("STREAM_CUE_BYTE_BOUNDARY")
            val end = byteBoundaries.getOrNull(cue.endByte)?.takeIf { it >= 0 } ?: error("STREAM_CUE_BYTE_BOUNDARY")
            require(cue.startFrame >= lastCueFrame && cue.endFrame > cue.startFrame && cue.endFrame <= MAX_STREAM_FRAMES) { "STREAM_CUE_FRAME_RANGE" }
            val range = if (sourceCharacters != null) {
                val selected = sourceCharacters.subList(start, end).filterNot(IntRange::isEmpty)
                if (selected.isEmpty()) IntRange.EMPTY else selected.minOf { it.first }..selected.maxOf { it.last }
            } else if (sourceRanges == null) {
                start until end
            } else {
                val selected = words.indices.filter { words[it].first < end && words[it].last >= start }.map { sourceRanges[it] }.filterNot(IntRange::isEmpty)
                if (selected.isEmpty()) IntRange.EMPTY else selected.minOf { it.first }..selected.maxOf { it.last }
            }
            lastCueByte = cue.startByte
            lastCueFrame = cue.endFrame
            maximumCueEnd = maxOf(maximumCueEnd, cue.endFrame)
            VoiceStreamCue(range, cue.startFrame, cue.endFrame, cue.provenance)
        }
        cues += mapped.size
        frames += chunk.samples.size
        return mapped
    }

    fun finish() {
        require(frames > 0 && maximumCueEnd <= frames) { "STREAM_INCOMPLETE" }
        require(nonSilent) { "STREAM_SILENT" }
    }

    companion object {
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
