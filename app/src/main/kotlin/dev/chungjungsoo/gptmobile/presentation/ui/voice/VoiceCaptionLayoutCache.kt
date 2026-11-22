package dev.chungjungsoo.gptmobile.presentation.ui.voice

/** Append-only text retains shaped lines; corrections rebuild from the corrected snapshot. */
internal class VoiceCaptionLayoutCache {
    private var previous = ""
    private var lines = emptyList<VoiceCaptionLine>()
    private var bidirectional = false

    fun update(text: String, measure: (String) -> List<VoiceCaptionLine>): List<VoiceCaptionLine> {
        if (text == previous && lines.isNotEmpty()) return lines
        val appended = text.startsWith(previous)
        val changed = if (appended) text.substring(previous.length) else text
        bidirectional = (appended && bidirectional) ||
            changed.codePoints().anyMatch {
                when (Character.getDirectionality(it)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC, Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE -> true
                    else -> false
                }
            }
        // Keep two trailing lines mutable: the final word can grow and move a wrap boundary.
        // Bidi paragraph direction can depend on preceding text; preserve full shaping there.
        val retained = if (appended && !bidirectional) (lines.size - 2).coerceAtLeast(0) else 0
        val start = lines.getOrNull(retained)?.start ?: 0
        val tail = measure(text.substring(start)).map { VoiceCaptionLine(it.start + start, it.end + start) }
        lines = lines.take(retained) + tail
        previous = text
        return lines
    }
}

internal class VoiceCaptionWordCache {
    private var previous = ""
    private var words = emptyList<IntRange>()

    fun update(caption: VoiceCaption): List<IntRange> {
        if (caption.text == previous) return words
        val retain = if (caption.text.startsWith(previous)) (words.size - 2).coerceAtLeast(0) else 0
        val start = if (retain > 0) words[retain].first else 0
        words = words.take(retain) + caption.copy(text = caption.text.substring(start)).wordRanges().map { (it.first + start)..(it.last + start) }
        previous = caption.text
        return words
    }
}
