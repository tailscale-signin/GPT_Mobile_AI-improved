package dev.chungjungsoo.gptmobile.data.voice

/** A bounded speech projection. Captions retain the original answer, links and code. */
internal object VoiceSpeechText {
    private const val CODE_NOTICE = "Code is available in the chat."
    private val url = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+")
    private val reference = Regex("(?:[0-9a-fA-F]{32,}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})")
    private val citation = Regex("[0-9]+(?:\\s*[,–-]\\s*[0-9]+)*")
    private val inlineProse = Regex("[\\p{L}\\p{N} .,'’!?_-]{1,120}")
    private val htmlTag = Regex("/?(?:a|b|br|blockquote|code|del|div|em|h[1-6]|hr|i|img|li|ol|p|pre|span|strong|sub|sup|table|tbody|td|th|thead|tr|u|ul)(?:\\s+[^<>]*)?/?", RegexOption.IGNORE_CASE)

    /** Incomplete constructs and words are held until their spoken form is stable. */
    fun project(input: String, final: Boolean): String {
        val limit = minOf(input.length, 12_000)
        val end = if (limit < input.length && input[limit - 1].isHighSurrogate()) limit - 1 else limit
        val text = input.substring(0, end)
        val out = StringBuilder()
        var index = 0
        fun comparison(words: String) {
            if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
            out.append(words)
            if (text.getOrNull(index + 1)?.isWhitespace() != true) out.append(' ')
        }
        while (index < text.length) {
            val char = text[index]
            if (text.startsWith("<!--", index)) {
                val close = text.indexOf("-->", index + 4)
                if (close < 0) break
                index = close + 3
                continue
            }
            if (char == '\uE200') {
                val close = text.indexOf('\uE201', index + 1)
                if (close < 0) break
                index = close + 1
                continue
            }
            if (text.startsWith("```", index) || text.startsWith("~~~", index)) {
                val fence = text.substring(index, index + 3)
                out.append(CODE_NOTICE)
                val close = text.indexOf(fence, index + 3)
                if (close < 0) break
                index = close + 3
                continue
            }
            if (char == '`') {
                val close = text.indexOf('`', index + 1)
                if (close < 0) {
                    if (final) out.append(CODE_NOTICE)
                    break
                }
                val code = text.substring(index + 1, close)
                out.append(if (inlineProse.matches(code) && !reference.matches(code)) code.replace('_', ' ') else CODE_NOTICE)
                index = close + 1
                continue
            }
            if (char == '[' || char == '!' && text.getOrNull(index + 1) == '[') {
                val start = if (char == '!') index + 1 else index
                val close = text.indexOf(']', start + 1)
                if (close < 0) {
                    if (!final) break
                    index = start + 1
                    continue
                }
                if (close == text.lastIndex && !final) break
                val label = text.substring(start + 1, close)
                val next = text.getOrNull(close + 1)
                if (next == '(' || next == '[') {
                    val destinationEnd = closingDelimiter(text, close + 1, next, if (next == '(') ')' else ']')
                    if (destinationEnd < 0 && !final) break
                    if (!citation.matches(label)) out.append(label.replace(url, "link").replace("*", "").replace("_", " "))
                    if (destinationEnd < 0) break
                    index = destinationEnd + 1
                } else if (next == ':' && (index == 0 || text[index - 1] == '\n')) {
                    val lineEnd = text.indexOf('\n', close + 2)
                    if (lineEnd < 0) break
                    index = lineEnd
                } else {
                    if (!citation.matches(label)) out.append(label.replace(url, "link"))
                    index = close + 1
                }
                continue
            }
            if (char == '<') {
                val next = text.getOrNull(index + 1)
                if (next?.isWhitespace() == true || next?.isDigit() == true || next in listOf('=', '+', '-')) {
                    comparison("less than")
                    index++
                    continue
                }
                val close = text.indexOf('>', index + 1)
                if (close < 0) {
                    if (!final) break
                    if (url.matchAt(text, index + 1) == null) comparison("less than")
                    index++
                    continue
                }
                val contents = text.substring(index + 1, close)
                if (url.matches(contents) || htmlTag.matches(contents)) {
                    if (url.matches(contents)) out.append("link")
                    index = close + 1
                } else {
                    comparison("less than")
                    index++
                }
                continue
            }
            if (char == '>') {
                if (!final && index == text.lastIndex) break
                if (index != 0 && text[index - 1] != '\n') comparison("greater than")
                index++
                continue
            }
            if (char.isWhitespace()) {
                out.append(char)
                index++
                continue
            }
            val address = url.matchAt(text, index)
            if (address != null) {
                if (!final && index + address.value.length == text.length) break
                out.append("link")
                address.value.lastOrNull()?.takeIf { it in ".,!?;:" }?.let(out::append)
                index += address.value.length
                continue
            }
            if (char in "*_#|" || char == '~') {
                index++
                continue
            }
            var tokenEnd = index + 1
            while (tokenEnd < text.length && !text[tokenEnd].isWhitespace() && text[tokenEnd] !in "`[<>!*_#|~\uE200") tokenEnd++
            if (tokenEnd == text.length && !final) break
            val token = text.substring(index, tokenEnd)
            out.append(
                if (reference.matches(token.trimEnd('.', ',', ';', ':'))) {
                    "reference"
                } else {
                    token.replace(url, "link").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                }
            )
            index = tokenEnd
        }
        return out.toString()
    }

    private fun closingDelimiter(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == open) {
                depth++
            } else if (char == close && --depth == 0) {
                return index
            }
        }
        return -1
    }
}
