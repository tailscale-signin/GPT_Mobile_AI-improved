package dev.chungjungsoo.gptmobile.data.voice

/** Bounded lexical state shared by saved-text projection and streamed protocol reasoning. */
internal class ReasoningTagScanner(private val name: String, private val exactTags: Boolean = false) {
    data class Result(val consumed: Int, val closingStart: Int? = null)

    private val literals = LiteralState()
    private var nesting = 1

    fun scan(text: CharSequence, start: Int = 0, final: Boolean = false): Result {
        var index = start
        while (index < text.length) {
            val literal = literals.consume(text[index])
            if (!literal && text[index] == '<') {
                val tag = tagAt(text, index, final)
                if (tag.pending) return Result(index)
                if (tag.end >= 0) {
                    if (tag.closing) {
                        nesting--
                    } else if (!tag.selfClosing) {
                        nesting++
                    }
                    if (nesting == 0) return Result(tag.end, index)
                    for (offset in index + 1 until tag.end) literals.markup(text[offset])
                    index = tag.end
                    continue
                }
            }
            index++
        }
        return Result(index)
    }

    private data class Tag(val end: Int = -1, val closing: Boolean = false, val selfClosing: Boolean = false, val pending: Boolean = false)

    private fun tagAt(text: CharSequence, start: Int, final: Boolean): Tag {
        var index = start + 1
        val closing = text.getOrNull(index) == '/'
        if (closing) index++
        for (character in name) {
            if (index == text.length) return Tag(pending = !final)
            if (!text[index].equals(character, ignoreCase = true)) return Tag()
            index++
        }
        if (index == text.length) return Tag(pending = !final)
        if (exactTags) return if (text[index] == '>') Tag(index + 1, closing) else Tag()
        if (!text[index].isWhitespace() && text[index] !in "/>") return Tag()
        var quote: Char? = null
        var escaped = false
        while (index < text.length) {
            val character = text[index]
            when {
                escaped -> escaped = false
                quote != null && character == '\\' -> escaped = true
                quote != null -> if (character == quote) quote = null
                character in "\"'" -> quote = character
                character == '>' -> return Tag(index + 1, closing, (start + 1 until index).lastOrNull { !text[it].isWhitespace() }?.let { text[it] == '/' } == true)
                character == '<' -> return Tag()
            }
            index++
        }
        return Tag(pending = !final)
    }

    private class LiteralState {
        private var previous: Char? = null
        private var quote: Char? = null
        private var possibleQuoteEnd = false
        private var escaped = false
        private var marker: Char? = null
        private var markerLength = 0
        private var markerCanFence = false
        private var inlineWidth = 0
        private var fence: Char? = null
        private var fenceWidth = 0
        private var fenceOpeningLine = false
        private var fenceStage = 0
        private var fenceCount = 0
        private var fenceIndent = 0
        private var lineIndent = 0
        private var lineStart = false
        private var openingPrefix = true
        private var indentedCode = false
        private var listPrefix = 0
        private var headingLength = 0

        fun markup(character: Char) {
            previous = character
            if (character == '\n') resetLine() else lineStart = false
        }

        fun consume(character: Char): Boolean {
            // The body can begin directly with a fence after the protocol tag,
            // without reclassifying initial reasoning indentation as code.
            val canStartFence = (openingPrefix || lineStart) && lineIndent < 4
            if (possibleQuoteEnd) {
                if (!character.isLetterOrDigit()) quote = null
                possibleQuoteEnd = false
            }
            if (marker != null && character != marker) {
                if (inlineWidth > 0) {
                    if (markerLength == inlineWidth) inlineWidth = 0
                } else if (markerLength >= 3 && markerCanFence) {
                    fence = marker
                    fenceWidth = markerLength
                    fenceOpeningLine = true
                } else if (marker == '`') {
                    inlineWidth = markerLength
                }
                marker = null
                markerLength = 0
                markerCanFence = false
            }
            val literal = when {
                marker != null -> {
                    markerLength++
                    true
                }
                fence != null -> {
                    consumeFence(character)
                    true
                }
                inlineWidth > 0 -> {
                    if (character == '`') startMarker(character)
                    true
                }
                escaped -> {
                    escaped = false
                    true
                }
                quote != null -> {
                    if (character == '\\') {
                        escaped = true
                    } else if (character == quote) {
                        if (character in "'’" && previous?.isLetterOrDigit() == true) {
                            // A chunk may end at the apostrophe in "isn't". Resolve it
                            // with the next character before closing the quoted literal.
                            possibleQuoteEnd = true
                        } else {
                            quote = null
                        }
                    }
                    true
                }
                indentedCode -> true
                consumeIndent(character) -> true
                character == '\\' -> {
                    escaped = true
                    true
                }
                character == '"' && previous?.isDigit() != true || character == '“' || character in "'‘" && previous?.isLetterOrDigit() != true -> {
                    quote = when (character) {
                        '“' -> '”'
                        '‘' -> '’'
                        else -> character
                    }
                    true
                }
                character == '`' || character == '~' -> {
                    startMarker(character, canStartFence)
                    true
                }
                else -> false
            }
            previous = character
            if (character == '\n') {
                resetLine()
            } else if (character != ' ' && character != '\t') {
                openingPrefix = false
                lineStart = false
            } else if (openingPrefix) {
                lineIndent = minOf(4, lineIndent + if (character == '\t') 4 else 1)
            }
            return literal
        }

        private fun startMarker(character: Char, canFence: Boolean = false) {
            marker = character
            markerLength = 1
            markerCanFence = canFence
        }

        private fun consumeFence(character: Char) {
            if (character == '\n') {
                if (!fenceOpeningLine && fenceStage in 1..2 && fenceCount >= fenceWidth) fence = null
                fenceOpeningLine = false
                fenceStage = 0
                fenceCount = 0
                fenceIndent = 0
            } else if (fenceOpeningLine && fence == '`' && character == '`') {
                // Backticks are not allowed in a backtick fence's info string.
                // Resolve this as an exact-width inline span, including when its
                // closing run arrives in a later stream chunk on the same line.
                fence = null
                inlineWidth = fenceWidth
                startMarker(character)
            } else if (!fenceOpeningLine) {
                when (fenceStage) {
                    0 -> when {
                        character == ' ' && fenceIndent < 3 -> fenceIndent++
                        character == fence -> {
                            fenceStage = 1
                            fenceCount = 1
                        }
                        else -> fenceStage = 3
                    }
                    1 -> when {
                        character == fence -> fenceCount++
                        character.isWhitespace() -> fenceStage = 2
                        else -> fenceStage = 3
                    }
                    2 -> if (!character.isWhitespace()) fenceStage = 3
                }
            }
        }

        private fun consumeIndent(character: Char): Boolean {
            if (listPrefix > 0) {
                when (listPrefix) {
                    1 -> if (character == '#' && headingLength < 6) {
                        headingLength++
                        return true
                    }
                    3 -> {
                        if (character in '0'..'9') return true
                        if (character in ".)") {
                            listPrefix = 4
                            return true
                        }
                        indentedCode = true
                        listPrefix = 0
                        return true
                    }
                }
                indentedCode = !character.isWhitespace()
                listPrefix = 0
                return indentedCode
            }
            if (!lineStart) return false
            if (character == ' ' || character == '\t') {
                lineIndent = minOf(4, lineIndent + if (character == '\t') 4 else 1)
                return false
            }
            lineStart = false
            if (lineIndent < 4 || character == '\n') return false
            listPrefix = when {
                character == '#' -> 1
                character in ">-+*" -> 2
                character in '0'..'9' -> 3
                else -> 0
            }
            indentedCode = listPrefix == 0
            headingLength = if (listPrefix == 1) 1 else 0
            return true
        }

        private fun resetLine() {
            lineIndent = 0
            lineStart = true
            openingPrefix = false
            indentedCode = false
            listPrefix = 0
            headingLength = 0
        }
    }
}
