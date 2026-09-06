package dev.melo.gptmobile.improved.presentation.ui.chat

sealed interface MarkdownToken {
    data class Text(val text: String) : MarkdownToken
    data class Bold(val text: String) : MarkdownToken
    data class Italic(val text: String) : MarkdownToken
    data class Code(val text: String) : MarkdownToken
    data class Strikethrough(val text: String) : MarkdownToken
    data class CodeBlock(val language: String?, val code: String) : MarkdownToken
}

object ChatMarkdownParser {
    fun parse(markdown: String): List<MarkdownToken> {
        val tokens = mutableListOf<MarkdownToken>()
        var index = 0
        val length = markdown.length

        while (index < length) {
            val codeBlockStart = markdown.indexOf("```", index)
            if (codeBlockStart == index) {
                val codeBlockEnd = markdown.indexOf("```", codeBlockStart + 3)
                if (codeBlockEnd != -1) {
                    val fullBlock = markdown.substring(codeBlockStart + 3, codeBlockEnd)
                    val firstNewline = fullBlock.indexOf('\n')
                    val lang = if (firstNewline != -1) fullBlock.substring(0, firstNewline).trim() else null
                    val code = if (firstNewline != -1) fullBlock.substring(firstNewline + 1) else fullBlock
                    tokens.add(MarkdownToken.CodeBlock(language = lang.takeIf { !it.isNullOrBlank() }, code = code))
                    index = codeBlockEnd + 3
                    continue
                }
            }

            // Inline checks
            val nextSpecial = markdown.indexOfAny(charArrayOf('*', '`', '~'), index)
            if (nextSpecial == -1) {
                tokens.add(MarkdownToken.Text(markdown.substring(index)))
                break
            }

            if (nextSpecial > index) {
                tokens.add(MarkdownToken.Text(markdown.substring(index, nextSpecial)))
                index = nextSpecial
            }

            if (markdown.startsWith("**", index)) {
                val end = markdown.indexOf("**", index + 2)
                if (end != -1) {
                    tokens.add(MarkdownToken.Bold(markdown.substring(index + 2, end)))
                    index = end + 2
                    continue
                }
            }

            if (markdown.startsWith("`", index)) {
                val end = markdown.indexOf("`", index + 1)
                if (end != -1) {
                    tokens.add(MarkdownToken.Code(markdown.substring(index + 1, end)))
                    index = end + 1
                    continue
                }
            }

            if (markdown.startsWith("~~", index)) {
                val end = markdown.indexOf("~~", index + 2)
                if (end != -1) {
                    tokens.add(MarkdownToken.Strikethrough(markdown.substring(index + 2, end)))
                    index = end + 2
                    continue
                }
            }

            if (markdown.startsWith("*", index)) {
                val end = markdown.indexOf("*", index + 1)
                if (end != -1) {
                    tokens.add(MarkdownToken.Italic(markdown.substring(index + 1, end)))
                    index = end + 1
                    continue
                }
            }

            tokens.add(MarkdownToken.Text(markdown[index].toString()))
            index++
        }

        return tokens
    }
}
