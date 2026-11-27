package dev.chungjungsoo.gptmobile.data.voice

/** A bounded, append-only UTF-8 projection shared by the input and PCM workers. */
internal class VoiceIncrementalTextMap {
    private var byteBoundaries = IntArray(32)
    private var byteCount = 0
    private var characterCount = 0
    private var contentEnd = 0
    private var sourceCharacters: MutableList<IntRange>? = null

    @Synchronized fun append(text: String, sources: List<IntRange>? = null) {
        require(sources == null || sources.size == text.length)
        val remaining = VoiceIncrementalStreamLimits.MAX_INPUT_BYTES - byteCount
        require(text.length <= remaining) { "STREAM_INPUT_LIMIT" }
        val boundaries = VoiceStreamFrames.utf8Boundaries(text)
        val addedBytes = boundaries.size - 1
        require(addedBytes <= remaining) { "STREAM_INPUT_LIMIT" }
        val needed = byteCount + boundaries.size
        if (needed > byteBoundaries.size) {
            val capacity = maxOf(needed, byteBoundaries.size * 2).coerceAtMost(VoiceIncrementalStreamLimits.MAX_INPUT_BYTES + 1)
            byteBoundaries = byteBoundaries.copyOf(capacity)
        }
        for (i in 1 until boundaries.size) {
            val boundary = boundaries[i]
            byteBoundaries[byteCount + i] = if (boundary < 0) -1 else characterCount + boundary
        }
        // SDK timestamps refer to the input itself. Only projected pronunciation text
        // needs a source range per character, and a stream may start with ordinary text.
        if (sources != null && sourceCharacters == null) {
            sourceCharacters = MutableList(characterCount) { it..it }
        }
        sourceCharacters?.let { mapped ->
            if (sources != null) {
                mapped.addAll(sources)
            } else {
                for (i in text.indices) mapped.add((characterCount + i)..(characterCount + i))
            }
        }
        byteCount += addedBytes
        val lastContent = text.indexOfLast { !it.isWhitespace() }
        if (lastContent >= 0) {
            // Some voices omit closing punctuation from the final word timestamp.
            // A spoken symbol such as the plus signs in C++ must still be covered.
            var end = lastContent
            while (end > 0 && (text[end] in VOICE_CUE_PUNCTUATION || text[end].isWhitespace())) end--
            contentEnd = characterCount + (if (text[end].isLetterOrDigit()) end else lastContent) + 1
        }
        characterCount += text.length
    }

    @Synchronized fun range(startByte: Int, endByte: Int): IntRange {
        require(startByte >= 0 && endByte > startByte) { "STREAM_CUE_BYTE_BOUNDARY" }
        val start = boundaryAt(startByte)
        val end = boundaryAt(endByte)
        check(start >= 0 && end >= 0) { "STREAM_CUE_BYTE_BOUNDARY" }
        val mapped = sourceCharacters ?: return start until end
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (i in start until end) {
            val range = mapped[i]
            if (!range.isEmpty()) {
                first = minOf(first, range.first)
                last = maxOf(last, range.last)
            }
        }
        return if (first > last) IntRange.EMPTY else first..last
    }

    @Synchronized fun coversSubmittedText(endByte: Int): Boolean = contentEnd > 0 && boundaryAt(endByte) >= contentEnd

    @Synchronized fun contains(startByte: Int, endByte: Int): Boolean =
        endByte > startByte && boundaryAt(startByte) >= 0 && boundaryAt(endByte) >= 0

    private fun boundaryAt(byte: Int): Int = if (byte in 0..byteCount) byteBoundaries[byte] else -1
}

internal data class VoiceReplyStreamCaption(val segment: Int, val text: String, val cue: VoiceStreamCue)

/** Each source segment retains its own caption identity, even though the SDK sees one input stream. */
internal class VoiceReplyStreamText(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val projection = VoiceIncrementalTextMap()
    private data class Segment(val index: Int, val text: String, val offset: Int)
    private val segments = mutableListOf<Segment>()
    private var sourceCharacters = 0
    private var lastCharacter: Char? = null
    private var renderedAt: Long? = null
    private var textWaitMs = 0L

    @Synchronized fun allSubmittedTextRendered(): Boolean = renderedAt != null

    // This clock excludes only time after the SDK supplied PCM for its final submitted
    // word. An idle input feeder alone says nothing about work still inside the SDK.
    @Synchronized fun deliveryClockMs(): Long = (renderedAt ?: nowMs()) - textWaitMs

    @Synchronized fun observeRenderedText(cues: List<VoiceWordCue>, receivedFrames: Long) {
        val cue = cues.lastOrNull { it.finalEnd && it.endFrame <= receivedFrames } ?: return
        if (renderedAt == null && projection.coversSubmittedText(cue.endByte)) renderedAt = nowMs()
    }

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
        renderedAt?.let { textWaitMs += nowMs() - it }
        renderedAt = null
        lastCharacter = text.lastOrNull() ?: lastCharacter
        return text
    }

    @Synchronized fun captions(cues: List<VoiceStreamCue>): List<VoiceReplyStreamCaption> = cues.mapNotNull { cue ->
        if (cue.range.isEmpty()) return@mapNotNull null
        val found = segments.binarySearchBy(cue.range.first) { it.offset }
        val source = segments.getOrNull(if (found >= 0) found else -found - 2)
            ?.takeIf { cue.range.last < it.offset + it.text.length }
            ?: error("STREAM_CUE_SOURCE_BOUNDARY")
        VoiceReplyStreamCaption(source.index, source.text, cue.copy(range = (cue.range.first - source.offset)..(cue.range.last - source.offset)).lexicalRange(source.text))
    }
}
