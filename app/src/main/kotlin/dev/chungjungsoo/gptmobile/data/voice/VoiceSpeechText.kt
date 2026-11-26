package dev.chungjungsoo.gptmobile.data.voice

/** One bounded, append-stable speech projection for live playback, replay and their captions. */
internal object VoiceSpeechText {
    const val LIMIT_NOTICE = "The rest of this response is available in the chat."

    /** Incomplete words and markup stay pending, so later snapshots cannot expose a URL fragment. */
    fun project(input: String, final: Boolean = true, includeDetails: Boolean = false): String =
        projectBounded(input, final, includeDetails, preserveLiteralTerms = false)

    /** Engines match saved literal terms after safety filtering and before default identifier rendering. */
    fun forPlayback(input: String, final: Boolean = true, includeDetails: Boolean = false): String =
        projectBounded(input, final, includeDetails, preserveLiteralTerms = true)

    private fun projectBounded(input: String, final: Boolean, includeDetails: Boolean, preserveLiteralTerms: Boolean): String {
        val end = minOf(input.length, 12000).let { if (it > 0 && input[it - 1].isHighSurrogate()) it - 1 else it }
        val spoken = project(input.substring(0, end), final && input.length <= 12000, 0, Notices(preserveLiteralTerms = preserveLiteralTerms), completed = final, includeDetails)
        return if (final && input.length > 12000) {
            spoken + (if (spoken.isNotEmpty() && !spoken.last().isWhitespace()) " " else "") + LIMIT_NOTICE
        } else {
            spoken
        }
    }

    /** The original rich content stays on screen; this flag never exposes hidden reasoning. */
    fun hasDetails(input: String): Boolean {
        val visible = withoutReasoning(input)
        return visible.length > 12000 ||
            detailOpening.containsMatchIn(visible) ||
            tableOpening.containsMatchIn(visible) ||
            sourceHeading.containsMatchIn(visible) ||
            fenceOpening.containsMatchIn(visible) ||
            uriPrefix.containsMatchIn(visible) ||
            '\uE200' in visible
    }

    /** Rich display/replay source. Explicit reasoning is hidden even for older saved replies. */
    fun withoutReasoning(input: String, final: Boolean = true): String = visible(input, final, unwrapDetails = false)

    /** Expand explicit detail containers into Markdown while preserving ordinary rich text. */
    fun richDetails(input: String, final: Boolean = true): String = visible(input, final, unwrapDetails = true)

    private fun visible(input: String, final: Boolean, unwrapDetails: Boolean): String {
        val result = StringBuilder()
        var index = 0
        while (index < input.length) {
            if (index == 0 || input[index - 1] == '\n') {
                val end = input.indexOf('\n', index).let { if (it < 0) input.length else it }
                val line = input.substring(index, end)
                val content = line.trimStart(' ', '\t')
                if ((line.length - content.length >= 4 || line.startsWith('\t')) && lineMarker.find(content) == null) {
                    result.append(input, index, end)
                    index = end
                    if (index == input.length) break
                }
            }
            if (input[index] == '\\' && index + 1 < input.length) {
                result.append(input, index, index + 2)
                index += 2
                continue
            }
            if (input[index] == '`' || input.startsWith("~~~", index)) {
                val markerEnd = input.indexOfFirstFrom(index) { it != input[index] }
                val marker = input.substring(index, markerEnd)
                val end = if (marker.length >= 3) {
                    val lineEnd = input.indexOf('\n', markerEnd)
                    if (lineEnd < 0) input.length else fenceEnd(input, lineEnd, marker, final = true).takeIf { it >= 0 } ?: input.length
                } else {
                    input.indexOf(marker, markerEnd).let { if (it < 0) input.length else it + marker.length }
                }
                result.append(input, index, end)
                index = end
                continue
            }
            if (isQuoteStart(input, index)) {
                val end = quotedEnd(input, index, final).let { if (it < 0) input.length else it + 1 }
                result.append(input, index, end)
                index = end
                continue
            }
            if (input[index] == '<') {
                val close = tagEnd(input, index)
                if (close < 0) {
                    val tail = input.substring(index + 1).removePrefix("/").lowercase()
                    if ((!final && tail.isEmpty()) || tail.isNotEmpty() && reasoningTags.any { it.startsWith(tail) || tail.startsWith("$it ") }) break
                } else {
                    val tag = input.substring(index + 1, close)
                    val name = htmlTagName.find(tag)?.groupValues?.get(1)?.lowercase()
                    if (name in reasoningTags) {
                        if (tag.startsWith('/') || tag.trimEnd().endsWith('/')) {
                            index = close + 1
                        } else {
                            val end = pairedTagEnd(input, close + 1, requireNotNull(name))
                            if (end < 0) break
                            if (result.isNotEmpty() && !result.last().isWhitespace() && input.getOrNull(end)?.isWhitespace() == false) result.append(' ')
                            index = end
                        }
                        continue
                    }
                    if (unwrapDetails && name in displayContainers) {
                        result.append('\n')
                        index = close + 1
                        continue
                    }
                }
            }
            result.append(input[index++])
        }
        return result.toString()
    }

    private fun quotedEnd(text: String, start: Int, final: Boolean): Int {
        val close = when (text[start]) {
            '“' -> '”'
            '‘' -> '’'
            else -> text[start]
        }
        var escaped = false
        for (index in start + 1 until text.length) {
            if (escaped) {
                escaped = false
            } else if (text[index] == '\\') {
                escaped = true
            } else if (text[index] == close &&
                !(close in "'’" && text[index - 1].isLetterOrDigit() && (text.getOrNull(index + 1)?.isLetterOrDigit() == true || index == text.lastIndex && !final))
            ) {
                return index
            }
        }
        return -1
    }

    private fun isQuoteStart(text: String, index: Int): Boolean =
        text[index] in "\"“" || text[index] in "'‘" && text.getOrNull(index - 1)?.isLetterOrDigit() != true

    private class Notices(var codeAnnounced: Boolean = false, val preserveLiteralTerms: Boolean = false)

    private fun project(text: String, final: Boolean, depth: Int, notices: Notices, completed: Boolean = final, includeDetails: Boolean = false, literalTags: Boolean = false): String {
        if (depth > 4) return ""
        val stableEnd = if (final) text.length else text.indexOfLast { it.isWhitespace() || it in "。！？।॥" } + 1
        val spoken = StringBuilder()
        var index = 0
        fun codeNotice() {
            if (spoken.isNotEmpty() && !spoken.last().isWhitespace()) spoken.append(' ')
            if (!notices.codeAnnounced) {
                spoken.append(CODE_NOTICE).append(' ')
                notices.codeAnnounced = true
            }
        }
        fun comparison(words: String) {
            if (spoken.isNotEmpty() && !spoken.last().isWhitespace()) spoken.append(' ')
            spoken.append(words)
            if (text.getOrNull(index + 1)?.isWhitespace() != true) spoken.append(' ')
        }
        while (index < stableEnd) {
            // A link label is inline text, even when it begins with a number or list marker.
            if (depth == 0 && (index == 0 || text[index - 1] == '\n')) {
                val lineEnd = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                val content = (index until lineEnd).firstOrNull { text[it] != ' ' && text[it] != '\t' } ?: lineEnd
                if (content == text.length && !final) break
                val line = text.substring(content, lineEnd)
                if (heading.matches(line) && lineEnd == text.length && !completed) break
                if (!includeDetails && sourceHeading.matches(line)) {
                    val level = line.takeWhile { it == '#' }.length
                    index = sourceSectionEnd(text, lineEnd, level)
                    continue
                }
                // A leading pipe is unambiguous before any header word can enter the queue.
                // Bare headers are deliberately kept: recognizing a later pipe cannot retract
                // words already spoken. Voice prompts place rich tables inside <details>.
                if (line.startsWith('|')) {
                    val table = table(text, content, final, includeDetails, depth, notices)
                    if (table != null) {
                        spoken.append(table.speech)
                        if (table.pending) break
                        index = table.end
                        continue
                    }
                }
                val fence = fenceStart.find(line)?.value
                if (fence != null) {
                    // Defer an incomplete language tag so streamed and saved speech agree.
                    if (lineEnd == text.length && !completed) break
                    if (line.drop(fence.length).trim().equals("gpt-ui", ignoreCase = true)) {
                        spoken.append("Data is available in the chat. ")
                    } else {
                        codeNotice()
                    }
                    val close = fenceEnd(text, lineEnd, fence, final)
                    if (close < 0) break
                    spoken.append('\n')
                    index = close
                    continue
                }
                val marker = lineMarker.find(line)?.value
                val indentedCode = (content - index >= 4 || '\t' in text.substring(index, content)) && marker == null
                if (indentedCode || referenceDefinition.containsMatchIn(line) || tableRule.matches(line)) {
                    if (lineEnd == text.length && !final) break
                    if (indentedCode) codeNotice()
                    spoken.append('\n')
                    index = (lineEnd + 1).coerceAtMost(text.length)
                    continue
                }
                index = content
                if (marker != null) index += marker.length
                if (index >= stableEnd) break
            }
            val character = text[index]
            val literalTerm = if (notices.preserveLiteralTerms) safeLiteralTermAt(text, index, final, literalTags) else null
            when {
                !literalTags && isQuoteStart(text, index) -> {
                    val close = quotedEnd(text, index, final && completed)
                    val end = close.takeIf { it >= 0 } ?: text.length
                    spoken.append(character)
                    spoken.append(project(text.substring(index + 1, end), close >= 0 || final && completed, depth + 1, notices, completed, includeDetails, literalTags = true))
                    if (close >= 0) spoken.append(text[close])
                    index = if (close >= 0) close + 1 else text.length
                }
                character == '\uE200' -> {
                    val close = text.indexOf('\uE201', index + 1)
                    if (close < 0) break
                    index = close + 1
                }
                character == '`' || character == '~' -> {
                    val endMarker = text.indexOfFirstFrom(index) { it != character }
                    val marker = text.substring(index, endMarker)
                    if (marker.length >= 3) {
                        codeNotice()
                        val lineEnd = text.indexOf('\n', endMarker)
                        if (lineEnd < 0) break
                        val close = fenceEnd(text, lineEnd, marker, final)
                        if (close < 0) break
                        spoken.append('\n')
                        index = close
                        continue
                    }
                    if (character == '~') {
                        index = endMarker
                        continue
                    }
                    val close = text.indexOf(marker, endMarker)
                    if (close < 0) {
                        if (completed) codeNotice()
                        break
                    }
                    val content = text.substring(endMarker, close)
                    val readable = inlineSpeech(content)
                    if (readable != null) spoken.append(readable) else codeNotice()
                    index = close + marker.length
                }
                character == '[' || character == '!' && text.getOrNull(index + 1) == '[' -> {
                    val open = if (character == '!') index + 1 else index
                    val close = closing(text, open, '[', ']')
                    if (close < 0 || close == text.lastIndex && !completed) break
                    var after = close + 1
                    if (text.getOrNull(after) == '(') {
                        val destinationEnd = closing(text, after, '(', ')')
                        if (destinationEnd < 0 && !completed) break
                        after = if (destinationEnd < 0) text.length else destinationEnd + 1
                    } else if (text.getOrNull(after) == '[') {
                        val referenceEnd = closing(text, after, '[', ']')
                        if (referenceEnd < 0 && !completed) break
                        after = if (referenceEnd < 0) text.length else referenceEnd + 1
                    }
                    val label = text.substring(open + 1, close).replace(escapedLabelBracket, "")
                    if (!citation.matches(label)) spoken.append(project(label, final = true, depth + 1, notices).replace(labelWhitespace, " ").trim())
                    index = after
                }
                character == '<' -> {
                    if (text.startsWith("<!--", index)) {
                        val close = text.indexOf("-->", index + 4)
                        if (close < 0) break
                        index = close + 3
                        continue
                    }
                    val next = text.getOrNull(index + 1)
                    if (next?.isWhitespace() == true || next?.isDigit() == true || next in listOf('=', '+', '-')) {
                        comparison(if (next == '=') "less than or equal to" else "less than")
                        index += if (next == '=') 2 else 1
                        continue
                    }
                    val close = tagEnd(text, index)
                    if (close < 0) {
                        if (!final || next != null && rawLinkAt(text, index + 1) || isPendingNonspokenTag(text.substring(index + 1))) break
                        comparison("less than")
                        index++
                        continue
                    }
                    val tag = text.substring(index + 1, close)
                    val tagName = htmlTagName.find(tag)?.groupValues?.get(1)?.lowercase()
                    if (literalTags && (tagName in reasoningTags || tagName in detailTags || tagName in answerTags || tagName in htmlTags)) {
                        if (spoken.isNotEmpty() && !spoken.last().isWhitespace()) spoken.append(' ')
                        spoken.append(if (tag.startsWith('/')) "closing " else "opening ").append(tagName).append(" tag")
                        if (text.getOrNull(close + 1)?.isWhitespace() == false) spoken.append(' ')
                        index = close + 1
                        continue
                    }
                    if (tagName in reasoningTags || !includeDetails && tagName in detailTags) {
                        if (tag.startsWith('/') || tag.trimEnd().endsWith('/')) {
                            index = close + 1
                        } else {
                            val end = pairedTagEnd(text, close + 1, requireNotNull(tagName))
                            if (end < 0) break
                            if (spoken.isNotEmpty() && !spoken.last().isWhitespace() && text.getOrNull(end)?.isWhitespace() == false) spoken.append(' ')
                            index = end
                        }
                        continue
                    }
                    val hidden = hiddenTag.find(tag)?.groupValues?.get(1)
                    if (hidden != null) {
                        codeNotice()
                        val end = text.indexOf("</$hidden>", close + 1, ignoreCase = true)
                        if (end < 0) break
                        index = end + hidden.length + 3
                    } else if (tag.isNotEmpty() && rawLinkAt(tag, 0)) {
                        index = close + 1
                    } else if (tagName in htmlTags || tagName in detailTags || tagName in answerTags || tag.startsWith('!') || tag.startsWith('?')) {
                        if (lineBreakTag.containsMatchIn(tag)) spoken.append('\n')
                        index = close + 1
                    } else {
                        comparison("less than")
                        index++
                    }
                }
                character == '>' -> {
                    val equal = text.getOrNull(index + 1) == '='
                    comparison(if (equal) "greater than or equal to" else "greater than")
                    index += if (equal) 2 else 1
                }
                rawLinkAt(text, index) -> {
                    val end = text.indexOfFirstFrom(index, Char::isWhitespace)
                    // Source addresses remain readable on screen, never repeated as "link".
                    text.getOrNull(end - 1)?.takeIf { it in ".,!?;:" }?.let(spoken::append)
                    index = end
                }
                (index == 0 || !text[index - 1].isLetterOrDigit()) && character.isLetterOrDigit() && reference.matchAt(text, index) != null -> {
                    val match = requireNotNull(reference.matchAt(text, index))
                    val end = match.range.last + 1
                    if (end == text.length && !final) break
                    if (text.getOrNull(end)?.isLetterOrDigit() == true) {
                        spoken.append(character)
                        index++
                    } else {
                        spoken.append("reference")
                        index = end
                    }
                }
                literalTerm != null -> {
                    spoken.append(text, literalTerm.first, literalTerm.last + 1)
                    index = literalTerm.last + 1
                }
                character == '&' -> {
                    val entity = entity.matchAt(text, index)
                    if (entity != null) {
                        val decoded = decodeEntity(entity.groupValues[1])
                        // Decode a scalar once. Encoded literal tags never become executable
                        // markup, and unknown entities keep their visible spelling.
                        spoken.append(decoded?.let(::spokenEntity) ?: entity.value)
                        index = entity.range.last + 1
                    } else {
                        spoken.append(character)
                        index++
                    }
                }
                character == '#' && text.getOrNull(index + 1)?.isDigit() == true -> {
                    spoken.append(if (notices.preserveLiteralTerms) "#" else "number ")
                    index++
                }
                character in "≤≥≠=×÷" -> {
                    comparison(
                        when (character) {
                            '≤' -> "less than or equal to"
                            '≥' -> "greater than or equal to"
                            '≠' -> "does not equal"
                            '×' -> "times"
                            '÷' -> "divided by"
                            else -> "equals"
                        }
                    )
                    index++
                }
                character == '*' -> {
                    val markerEnd = text.indexOfFirstFrom(index) { it != '*' }
                    val marker = text.substring(index, markerEnd)
                    val close = text.indexOf(marker, markerEnd)
                    val before = text.getOrNull(index - 1)
                    val mayOpenFormatting = marker.length <= 2 &&
                        (before == null || before.isWhitespace() || before in "([{\"'") &&
                        text.getOrNull(markerEnd)?.isWhitespace() == false
                    val formatting = mayOpenFormatting && close > markerEnd && !text[close - 1].isWhitespace()
                    if (formatting) {
                        spoken.append(project(text.substring(markerEnd, close), true, depth + 1, notices, includeDetails = includeDetails, literalTags = literalTags))
                        index = close + marker.length
                    } else if (mayOpenFormatting) {
                        if (!completed) break
                        index = markerEnd
                    } else {
                        val left = text.substring(0, index).lastOrNull { !it.isWhitespace() }
                        val right = text.substring(markerEnd).firstOrNull { !it.isWhitespace() }
                        if (marker.length == 1 && (left?.isLetterOrDigit() == true || left == ')') && (right?.isLetterOrDigit() == true || right == '(')) comparison("times")
                        index = markerEnd
                    }
                }
                character == '_' -> {
                    val endMarker = text.indexOfFirstFrom(index) { it != '_' }
                    // An identifier is two words, not a new word made by deleting its
                    // separator. Leading/trailing emphasis markers still remain silent.
                    if (text.getOrNull(index - 1)?.isLetterOrDigit() == true && text.getOrNull(endMarker)?.isLetterOrDigit() == true) spoken.append(' ')
                    index = endMarker
                }
                character in "~\\]" -> index++
                else -> {
                    spoken.append(character)
                    index++
                }
            }
        }
        return spoken.toString()
    }

    private fun safeLiteralTermAt(text: String, index: Int, final: Boolean, literalTags: Boolean): IntRange? {
        val term = VoicePronunciationOverrides.literalTermAt(text, index) ?: return null
        // A spelling span must never jump over a safety decision in the normal character walk.
        // For example, x_https://... contains a source address beginning after its underscore.
        for (cursor in term.first + 1..term.last) {
            if (!literalTags && isQuoteStart(text, cursor) || rawLinkAt(text, cursor)) return null
            if (!text[cursor - 1].isLetterOrDigit() && text[cursor].isLetterOrDigit()) {
                val referenceEnd = reference.matchAt(text, cursor)?.range?.last?.plus(1)
                if (referenceEnd != null && (referenceEnd == text.length && !final || text.getOrNull(referenceEnd)?.isLetterOrDigit() != true)) return null
            }
        }
        return term
    }

    private data class Table(val end: Int, val speech: String = "", val pending: Boolean = false)

    private fun table(text: String, start: Int, final: Boolean, includeDetails: Boolean, depth: Int, notices: Notices): Table? {
        val headerEnd = text.indexOf('\n', start)
        if (headerEnd < 0) return if (final) null else Table(text.length, pending = true)
        val dividerEnd = text.indexOf('\n', headerEnd + 1).let { if (it < 0) text.length else it }
        val divider = text.substring(headerEnd + 1, dividerEnd).trim()
        if (dividerEnd == text.length && !final && divider.all { it in "|:- \t" }) return Table(text.length, pending = true)
        val columns = cells(text.substring(start, headerEnd))
        val rules = cells(divider)
        if (columns.isEmpty() || columns.size != rules.size || rules.any { !tableCellRule.matches(it) }) return null
        val headers = columns.map { project(it, true, depth + 1, notices, includeDetails = true).trim() }
        val spoken = StringBuilder()
        var index = (dividerEnd + 1).coerceAtMost(text.length)
        while (index < text.length) {
            val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val row = text.substring(index, end).trim()
            if (row.isBlank() || !row.startsWith('|')) break
            if (end == text.length && !final) return Table(end, spoken.toString(), pending = true)
            if (includeDetails) {
                cells(row).forEachIndexed { column, raw ->
                    val value = project(raw, true, depth + 1, notices, includeDetails = true).trim()
                    if (value.isNotEmpty()) {
                        headers.getOrNull(column)?.takeIf(String::isNotEmpty)?.let { spoken.append(it).append(": ") }
                        spoken.append(value)
                        if (value.last() !in ".!?;") spoken.append('.')
                        spoken.append(' ')
                    }
                }
                spoken.append('\n')
            }
            index = (end + 1).coerceAtMost(text.length)
        }
        return Table(index, spoken.toString())
    }

    private fun cells(line: String): List<String> {
        val text = line.trim().removePrefix("|").removeSuffix("|")
        val result = mutableListOf<String>()
        var start = 0
        var escaped = false
        var code = false
        text.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '`' -> code = !code
                character == '|' && !code -> {
                    result += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += text.substring(start).trim()
        return result
    }

    private fun isPendingNonspokenTag(text: String): Boolean {
        val name = text.removePrefix("/").lowercase()
        return name.isNotEmpty() && (reasoningTags + detailTags).any { it.startsWith(name) || name.startsWith("$it ") }
    }

    private fun sourceSectionEnd(text: String, start: Int, level: Int): Int {
        var index = (start + 1).coerceAtMost(text.length)
        while (index < text.length) {
            val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val line = text.substring(index, end).trimStart()
            if (heading.matches(line) && line.takeWhile { it == '#' }.length <= level) return index
            index = (end + 1).coerceAtMost(text.length)
        }
        return text.length
    }

    private fun pairedTagEnd(text: String, start: Int, name: String): Int =
        ReasoningTagScanner(name).scan(text, start, final = true).let { if (it.closingStart == null) -1 else it.consumed }

    private fun closing(text: String, start: Int, open: Char, close: Char): Int {
        var nesting = 0
        var escaped = false
        var quoted: Char? = null
        for (index in start until text.length) {
            val character = text[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (quoted != null) {
                if (character == quoted) quoted = null
            } else if (open == '(' && character in "\"'" && text.getOrNull(index - 1)?.isWhitespace() == true) {
                quoted = character
            } else if (character == open) {
                nesting++
            } else if (character == close && --nesting == 0) {
                return index
            }
        }
        return -1
    }

    private fun fenceEnd(text: String, start: Int, marker: String, final: Boolean): Int {
        var index = start
        while (index < text.length) {
            val end = text.indexOf('\n', index + 1).let { if (it < 0) text.length else it }
            val rawLine = text.substring(index, end).removePrefix("\n").trimEnd()
            val line = rawLine.trimStart(' ')
            if ((end < text.length || final) && rawLine.length - line.length <= 3 && line.length >= marker.length && line.all { it == marker[0] }) return end
            index = end
        }
        return -1
    }

    private fun tagEnd(text: String, start: Int): Int {
        var quote: Char? = null
        for (index in start + 1 until text.length) {
            val character = text[index]
            if (quote != null) {
                if (character == quote) quote = null
            } else if (character in "\"'") {
                quote = character
            } else if (character == '>') {
                return index
            }
        }
        return -1
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int =
        (start until length).firstOrNull { predicate(this[it]) } ?: length

    private fun inlineSpeech(content: String): String? {
        if (content.length !in 1..120 || reference.matches(content) || uriPrefix.containsMatchIn(content)) return null
        if (inlineProse.matches(content)) return content
        return when {
            // Keep the original spelling until the selected engine applies saved
            // whole-term corrections and then its built-in pronunciation rules.
            content in setOf("C++", "c++", "C#", "c#", "F#", "f#") -> content
            inlineOption.matches(content) -> "dash dash " + content.removePrefix("--").replace('-', ' ')
            inlineTechnicalName.matches(content) || inlineRate.matches(content) || inlineQuantity.matches(content) -> content
            else -> null
        }
    }

    private fun decodeEntity(name: String): String? {
        namedEntities[name]?.let { return it }
        if (!name.startsWith('#')) return null
        val hexadecimal = name.getOrNull(1) in listOf('x', 'X')
        val codePoint = name.substring(if (hexadecimal) 2 else 1).toIntOrNull(if (hexadecimal) 16 else 10) ?: return null
        if (!Character.isValidCodePoint(codePoint) || codePoint in 0xD800..0xDFFF || Character.isISOControl(codePoint)) return null
        return String(Character.toChars(codePoint))
    }

    private fun spokenEntity(value: String): String = when (value) {
        "<" -> " less than "
        ">" -> " greater than "
        "≤" -> " less than or equal to "
        "≥" -> " greater than or equal to "
        "≠" -> " does not equal "
        "×" -> " times "
        "÷" -> " divided by "
        "±" -> " plus or minus "
        "≈" -> " approximately "
        "∞" -> " infinity "
        "\u00A0", "\u2002", "\u2003", "\u2009" -> " "
        "\u00AD", "\u200B", "\u200C", "\u200D", "\u2060", "\uFEFF" -> ""
        else -> value
    }

    private fun rawLinkAt(text: String, index: Int): Boolean {
        if (index > 0 && text[index - 1].isLetterOrDigit()) return false
        val webLink = when (text[index]) {
            'h', 'H' -> text.regionMatches(index, "https://", 0, 8, ignoreCase = true) || text.regionMatches(index, "http://", 0, 7, ignoreCase = true)
            'w', 'W' -> text.regionMatches(index, "www.", 0, 4, ignoreCase = true)
            else -> false
        }
        if (webLink || (index == 0 || text[index - 1] !in "+-.") && uriPrefix.matchAt(text, index) != null) return true
        if (index > 0 && text[index - 1] in "+-.") return false
        if (!text[index].isLetter() && text[index] != '&') return false
        // Decoding punctuation or letters must not expose an entity-encoded source URL.
        // Only inspect a bounded scheme/prefix, never decode arbitrary markup recursively.
        val prefix = StringBuilder()
        var cursor = index
        var decoded = false
        while (cursor < text.length && cursor - index < 256 && prefix.length < 128) {
            val encoded = if (text[cursor] == '&') entity.matchAt(text, cursor) else null
            val value = if (encoded == null) text[cursor].toString() else decodeEntity(encoded.groupValues[1]) ?: return false
            if (value.length != 1 || value[0] !in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+.-:/") return false
            prefix.append(value)
            decoded = decoded || encoded != null
            cursor = encoded?.range?.last?.plus(1) ?: cursor + 1
            if (decoded && uriPrefix.matchAt(prefix, 0) != null) return true
        }
        return false
    }

    private const val CODE_NOTICE = "Code is available in the chat."
    private val uriPrefix = Regex("(?:[a-z][a-z0-9+.-]*://|www\\.)", RegexOption.IGNORE_CASE)
    private val reference = Regex("(?:[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32,})")
    private val inlineProse = Regex("[\\p{L}\\p{N} .,'’!?]{1,120}")
    private val inlineOption = Regex("--[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*")
    private val inlineTechnicalName = Regex("[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+")
    private val inlineRate = Regex("(?:[kKMGT]?[Bb]|[ckm]?m|[mun]?g|frames|tokens)/(?:s|sec|min|h)")
    private val inlineQuantity = Regex("[$£€]?[+\\-−]?(?:[0-9]+(?:[.,][0-9]+)*|\\.[0-9]+)(?:[%°]|[ \\t]*(?:°[CF]|[µμ]g|[a-zA-Z]{1,5}(?:/[smh])?))?")
    private val fenceStart = Regex("^(`{3,}|~{3,})")
    private val lineMarker = Regex("^(?:#{1,6}|>|[-+*]|[0-9]+[.)])[ \\t]+")
    private val referenceDefinition = Regex("^\\[[^]\\n]+]:")
    private val tableRule = Regex("[|:= \\t-]*[-|=][|:= \\t-]{2,}")
    private val tableCellRule = Regex(":?-{3,}:?")
    private val citation = Regex("(?:[0-9, –-]+|\\^[^ ]+)")
    private val escapedLabelBracket = Regex("""\\[\[\]]""")
    private val labelWhitespace = Regex("\\s+")
    private val hiddenTag = Regex("^(pre|code|script|style)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val reasoningTags = setOf("think", "thinking", "analysis", "reasoning")
    private val detailTags = setOf("details", "voice_details", "sources", "references", "table")
    private val answerTags = setOf("answer", "final", "voice_answer", "speech", "summary")
    private val displayContainers = detailTags - "table" + answerTags
    private val detailOpening = Regex("<(?:details|voice_details|sources|references|table)(?:\\s|>)", RegexOption.IGNORE_CASE)
    private val tableOpening = Regex("(?m)^\\s*\\|[^\\n]+\\n[ \\t]*\\|?[ \\t]*:?-{3,}")
    private val sourceHeading = Regex("(?im)^#{1,6}[ \\t]+(?:sources|references|citations)[ \\t]*:?[ \\t]*$")
    private val heading = Regex("^#{1,6}[ \\t]+.*")
    private val fenceOpening = Regex("(?m)^[ \\t]*(`{3,}|~{3,})")
    private val htmlTagName = Regex("^/?([A-Za-z][A-Za-z0-9_-]*)(?=\\s|/|$)")
    private val htmlTags = setOf("a", "b", "br", "blockquote", "code", "del", "div", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "span", "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul")
    private val lineBreakTag = Regex("^/?(?:br|hr|p|div|li|h[1-6]|details|voice_details|sources|references|summary|table|thead|tbody|tr|td|th)(?:\\s|/|$)", RegexOption.IGNORE_CASE)
    private val entity = Regex("&([A-Za-z][A-Za-z0-9]*|#[0-9]+|#[xX][0-9a-fA-F]+);")
    private val namedEntities = buildMap {
        putAll(
            mapOf(
                "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "colon" to ":", "sol" to "/",
                "nbsp" to "\u00A0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009", "shy" to "\u00AD",
                "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "hellip" to "…",
                "ndash" to "–", "mdash" to "—", "minus" to "−", "plusmn" to "±", "times" to "×", "divide" to "÷",
                "le" to "≤", "ge" to "≥", "ne" to "≠", "asymp" to "≈", "infin" to "∞", "deg" to "°",
                "pound" to "£", "euro" to "€", "cent" to "¢", "yen" to "¥", "copy" to "©", "reg" to "®", "trade" to "™",
                "frac14" to "¼", "frac12" to "½", "frac34" to "¾", "micro" to "µ", "sup1" to "¹", "sup2" to "²", "sup3" to "³",
                "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š", "scaron" to "š", "Yuml" to "Ÿ"
            )
        )
        val names = "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        names.split(' ').forEachIndexed { index, name -> put(name, (0x00C0 + index).toChar().toString()) }
    }
}
