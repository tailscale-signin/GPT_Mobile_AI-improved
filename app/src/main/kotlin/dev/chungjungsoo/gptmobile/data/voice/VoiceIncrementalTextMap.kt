package dev.chungjungsoo.gptmobile.data.voice

/** A bounded, append-only UTF-8 projection shared by the input and PCM workers. */
internal class VoiceIncrementalTextMap {
    private val byteBoundaries = mutableListOf(0)
    private val characters = mutableListOf<IntRange>()

    @Synchronized fun append(text: String, sources: List<IntRange>? = null) {
        require(sources == null || sources.size == text.length)
        val boundaries = VoiceStreamFrames.utf8Boundaries(text)
        require(byteBoundaries.size - 1 + boundaries.size - 1 <= VoiceIncrementalStreamLimits.MAX_INPUT_BYTES) { "STREAM_INPUT_LIMIT" }
        val offset = characters.size
        for (index in 1 until boundaries.size) {
            byteBoundaries += boundaries[index].let { if (it < 0) -1 else offset + it }
        }
        characters += sources ?: text.indices.map { (offset + it)..(offset + it) }
    }

    @Synchronized fun range(startByte: Int, endByte: Int): IntRange {
        require(startByte >= 0 && endByte > startByte) { "STREAM_CUE_BYTE_BOUNDARY" }
        val start = byteBoundaries.getOrNull(startByte)?.takeIf { it >= 0 } ?: error("STREAM_CUE_BYTE_BOUNDARY")
        val end = byteBoundaries.getOrNull(endByte)?.takeIf { it >= 0 } ?: error("STREAM_CUE_BYTE_BOUNDARY")
        val selected = characters.subList(start, end).filterNot(IntRange::isEmpty)
        return if (selected.isEmpty()) IntRange.EMPTY else selected.minOf { it.first }..selected.maxOf { it.last }
    }

    @Synchronized fun contains(startByte: Int, endByte: Int): Boolean =
        startByte >= 0 &&
            endByte > startByte &&
            byteBoundaries.getOrNull(startByte)?.let { it >= 0 } == true &&
            byteBoundaries.getOrNull(endByte)?.let { it >= 0 } == true
}

internal data class VoiceReplyStreamCaption(val segment: Int, val text: String, val cue: VoiceStreamCue)

/** Each source segment retains its own caption identity, even though the SDK sees one input stream. */
internal class VoiceReplyStreamText {
    val projection = VoiceIncrementalTextMap()
    private data class Segment(val index: Int, val text: String, val offset: Int)
    private val segments = mutableListOf<Segment>()
    private var sourceCharacters = 0
    private var lastCharacter: Char? = null

    @Synchronized fun append(segment: Int, original: String, phrase: VoiceStreamingPhrase): String {
        val newSegment = segments.lastOrNull()?.index != segment
        val source = segments.lastOrNull()?.takeIf { it.index == segment } ?: run {
            require(segments.size < VoiceIncrementalStreamLimits.MAX_SEGMENTS) { "STREAM_SEGMENT_LIMIT" }
            require(sourceCharacters + original.length <= VoiceIncrementalStreamLimits.MAX_SOURCE_CHARACTERS) { "STREAM_SOURCE_LIMIT" }
            require(segments.lastOrNull()?.let { segment > it.index } != false) { "STREAM_SEGMENT_ORDER" }
            Segment(segment, original, sourceCharacters + segments.size).also {
                segments += it
                sourceCharacters += original.length
            }
        }
        require(source.text == original)
        require(phrase.sourceCharacters.size == phrase.text.length)
        require(phrase.sourceCharacters.all { it.isEmpty() || it.first >= 0 && it.last < original.length })
        val separator = newSegment && lastCharacter?.let { !it.isWhitespace() && phrase.text.firstOrNull()?.isWhitespace() == false } == true
        val text = (if (separator) " " else "") + phrase.text
        val ranges = (if (separator) listOf(IntRange.EMPTY) else emptyList()) + phrase.sourceCharacters.map {
            if (it.isEmpty()) it else (source.offset + it.first)..(source.offset + it.last)
        }
        projection.append(text, ranges)
        lastCharacter = text.lastOrNull() ?: lastCharacter
        return text
    }

    @Synchronized fun captions(cues: List<VoiceStreamCue>): List<VoiceReplyStreamCaption> = cues.mapNotNull { cue ->
        if (cue.range.isEmpty()) return@mapNotNull null
        val found = segments.binarySearchBy(cue.range.first) { it.offset }
        val source = segments.getOrNull(if (found >= 0) found else -found - 2)
            ?.takeIf { cue.range.last < it.offset + it.text.length }
            ?: error("STREAM_CUE_SOURCE_BOUNDARY")
        VoiceReplyStreamCaption(source.index, source.text, cue.copy(range = (cue.range.first - source.offset)..(cue.range.last - source.offset)))
    }
}
