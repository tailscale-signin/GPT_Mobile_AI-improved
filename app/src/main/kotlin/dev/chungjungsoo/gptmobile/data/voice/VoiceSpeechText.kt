package dev.chungjungsoo.gptmobile.data.voice

/** Speech-only projection of the buffer's bounded input; captions retain links and code. */
internal object VoiceSpeechText {
    /** Incomplete words and markup stay pending, so later snapshots cannot expose a URL fragment. */
    fun project(input: String, final: Boolean = true): String {
        val end = minOf(input.length, 12000).let { if (it > 0 && input[it - 1].isHighSurrogate()) it - 1 else it }
        return project(input.substring(0, end), final && input.length <= 12000, 0, Notices(), completed = final)
    }

    private class Notices(var codeAnnounced: Boolean = false)

    private fun project(text: String, final: Boolean, depth: Int, notices: Notices, completed: Boolean = final): String {
        if (depth > 4) return ""
        val stableEnd = if (final) text.length else text.indexOfLast(Char::isWhitespace) + 1
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
            if (index == 0 || text[index - 1] == '\n') {
                val lineEnd = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                val content = (index until lineEnd).firstOrNull { text[it] != ' ' && text[it] != '\t' } ?: lineEnd
                if (content == text.length && !final) break
                val line = text.substring(content, lineEnd)
                val fence = fenceStart.find(line)?.value
                if (fence != null) {
                    codeNotice()
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
            when {
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
                    if (inlineProse.matches(content) && !reference.matches(content)) spoken.append(content) else codeNotice()
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
                        comparison("less than")
                        index++
                        continue
                    }
                    val close = tagEnd(text, index)
                    if (close < 0) {
                        if (!final || next != null && rawLinkAt(text, index + 1)) break
                        comparison("less than")
                        index++
                        continue
                    }
                    val tag = text.substring(index + 1, close)
                    val hidden = hiddenTag.find(tag)?.groupValues?.get(1)
                    if (hidden != null) {
                        codeNotice()
                        val end = text.indexOf("</$hidden>", close + 1, ignoreCase = true)
                        if (end < 0) break
                        index = end + hidden.length + 3
                    } else if (tag.isNotEmpty() && rawLinkAt(tag, 0)) {
                        spoken.append("link")
                        index = close + 1
                    } else if (htmlTagName.find(tag)?.groupValues?.get(1)?.lowercase() in htmlTags || tag.startsWith('!') || tag.startsWith('?')) {
                        if (lineBreakTag.containsMatchIn(tag)) spoken.append('\n')
                        index = close + 1
                    } else {
                        comparison("less than")
                        index++
                    }
                }
                character == '>' -> {
                    comparison("greater than")
                    index++
                }
                rawLinkAt(text, index) -> {
                    val end = text.indexOfFirstFrom(index, Char::isWhitespace)
                    spoken.append("link")
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
                character == '&' -> {
                    val entity = entity.matchAt(text, index)
                    if (entity != null) {
                        spoken.append(
                            when (entity.groupValues[1]) {
                                "amp" -> "&"
                                "quot" -> "\""
                                "apos", "#39" -> "'"
                                else -> " "
                            }
                        )
                        index = entity.range.last + 1
                    } else {
                        spoken.append(character)
                        index++
                    }
                }
                character == '#' && text.getOrNull(index + 1)?.isDigit() == true -> {
                    spoken.append("number ")
                    index++
                }
                character in "*_~\\]" -> index++
                character == '|' -> {
                    spoken.append(' ')
                    index++
                }
                else -> {
                    spoken.append(character)
                    index++
                }
            }
        }
        return spoken.toString()
    }

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

    private fun rawLinkAt(text: String, index: Int): Boolean {
        if (index > 0 && text[index - 1].isLetterOrDigit()) return false
        val webLink = when (text[index]) {
            'h', 'H' -> text.regionMatches(index, "https://", 0, 8, ignoreCase = true) || text.regionMatches(index, "http://", 0, 7, ignoreCase = true)
            'w', 'W' -> text.regionMatches(index, "www.", 0, 4, ignoreCase = true)
            else -> false
        }
        return webLink || (index == 0 || text[index - 1] !in "+-.") && uriPrefix.matchAt(text, index) != null
    }

    private const val CODE_NOTICE = "Code is available in the chat."
    private val uriPrefix = Regex("(?:[a-z][a-z0-9+.-]*://|www\\.)", RegexOption.IGNORE_CASE)
    private val reference = Regex("(?:[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32,})")
    private val inlineProse = Regex("[\\p{L}\\p{N} .,'’!?]{1,120}")
    private val fenceStart = Regex("^(`{3,}|~{3,})")
    private val lineMarker = Regex("^(?:#{1,6}|>|[-+*]|[0-9]+[.)])[ \\t]+")
    private val referenceDefinition = Regex("^\\[[^]\\n]+]:")
    private val tableRule = Regex("[|:= \\t-]*[-|=][|:= \\t-]{2,}")
    private val citation = Regex("(?:[0-9, –-]+|\\^[^ ]+)")
    private val escapedLabelBracket = Regex("""\\[\[\]]""")
    private val labelWhitespace = Regex("\\s+")
    private val hiddenTag = Regex("^(pre|code|script|style)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val htmlTagName = Regex("^/?([A-Za-z][A-Za-z0-9]*)(?=\\s|/|$)")
    private val htmlTags = setOf("a", "b", "br", "blockquote", "code", "del", "div", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "span", "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul")
    private val lineBreakTag = Regex("^/?(?:br|hr|p|div|li|h[1-6])(?:\\s|/|$)", RegexOption.IGNORE_CASE)
    private val entity = Regex("&([A-Za-z][A-Za-z0-9]*|#[0-9]+|#x[0-9a-fA-F]+);")
}
