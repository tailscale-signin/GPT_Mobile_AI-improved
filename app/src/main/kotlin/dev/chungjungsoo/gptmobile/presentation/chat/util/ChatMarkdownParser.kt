package dev.chungjungsoo.gptmobile.presentation.chat.util

import java.util.UUID

sealed interface ChatMarkdownBlock {
    data class Markdown(val content: String) : ChatMarkdownBlock
    data class DisplayMath(val expression: String) : ChatMarkdownBlock
}

data class ParsedChatMarkdown(
    val blocks: List<ChatMarkdownBlock>,
    val inlineMath: List<InlineMathToken>
)

data class InlineMathToken(
    val placeholder: String,
    val expression: String
)

private const val INLINE_MATH_PREFIX = "INLINEMATH"
private const val DISPLAY_MATH_DOUBLE_DOLLAR = "$$"
private const val DISPLAY_MATH_BRACKET_OPEN = "\\["
private const val DISPLAY_MATH_BRACKET_CLOSE = "\\]"
private const val INLINE_MATH_PAREN_OPEN = "\\("
private const val INLINE_MATH_PAREN_CLOSE = "\\)"

/**
 * Splits markdown content into regular Markdown and Display Math blocks,
 * and extracts inline math expressions replaced by unique placeholders.
 *
 * It respects Markdown code blocks and inline code spans to avoid processing
 * LaTeX inside code.
 */
fun parseChatMarkdown(content: String): ParsedChatMarkdown {
    if (content.isEmpty()) {
        return ParsedChatMarkdown(emptyList(), emptyList())
    }

    // Fast-path: If content has no math delimiter indicators, skip parsing and allocations entirely.
    if (!content.contains('$') && !content.contains("\\[") && !content.contains("\\(")) {
        return ParsedChatMarkdown(
            blocks = listOf(ChatMarkdownBlock.Markdown(content)),
            inlineMath = emptyList()
        )
    }

    val blocks = mutableListOf<ChatMarkdownBlock>()
    val inlineMath = mutableListOf<InlineMathToken>()
    val markdownBuffer = StringBuilder(content.length)

    fun replaceInlineMath(text: String): String {
        // Fast-path: If segment does not contain inline math delimiters, return unchanged
        if (!text.contains('$') && !text.contains("\\(")) {
            return text
        }

        val sb = StringBuilder(text.length)
        var i = 0
        val len = text.length
        var inInlineCode = false

        while (i < len) {
            val char = text[i]

            if (char == '`') {
                inInlineCode = !inInlineCode
                sb.append(char)
                i++
                continue
            }

            if (!inInlineCode) {
                // Match \( ... \)
                if (text.startsWith(INLINE_MATH_PAREN_OPEN, i)) {
                    val startIndex = i + INLINE_MATH_PAREN_OPEN.length
                    val endIndex = text.indexOf(INLINE_MATH_PAREN_CLOSE, startIndex)
                    if (endIndex != -1) {
                        val expression = text.substring(startIndex, endIndex).trim()
                        if (expression.isNotEmpty()) {
                            val placeholder = "${INLINE_MATH_PREFIX}_${UUID.randomUUID().toString().replace("-", "")}"
                            inlineMath.add(InlineMathToken(placeholder, expression))
                            sb.append(placeholder)
                            i = endIndex + INLINE_MATH_PAREN_CLOSE.length
                            continue
                        }
                    }
                }

                // Match $ ... $ (ensuring it's not escaped or double dollar)
                if (char == '$' && (i == 0 || text[i - 1] != '\\')) {
                    val nextChar = text.getOrNull(i + 1)
                    if (nextChar != '$' && nextChar != null && !nextChar.isWhitespace()) {
                        // Find matching non-escaped $
                        var j = i + 1
                        var matchFound = false
                        while (j < len) {
                            if (text[j] == '$' && text[j - 1] != '\\') {
                                if (!text[j - 1].isWhitespace()) {
                                    val expression = text.substring(i + 1, j).trim()
                                    if (expression.isNotEmpty()) {
                                        val placeholder = "${INLINE_MATH_PREFIX}_${UUID.randomUUID().toString().replace("-", "")}"
                                        inlineMath.add(InlineMathToken(placeholder, expression))
                                        sb.append(placeholder)
                                        i = j + 1
                                        matchFound = true
                                        break
                                    }
                                }
                                break
                            }
                            j++
                        }
                        if (matchFound) {
                            continue
                        }
                    }
                }
            }

            sb.append(char)
            i++
        }
        return sb.toString()
    }

    fun flushMarkdownBuffer() {
        if (markdownBuffer.isNotEmpty()) {
            val raw = markdownBuffer.toString()
            val processed = replaceInlineMath(raw)
            blocks.add(ChatMarkdownBlock.Markdown(processed))
            markdownBuffer.clear()
        }
    }

    var i = 0
    val len = content.length
    var inFencedCodeBlock = false
    var inInlineCode = false

    while (i < len) {
        val char = content[i]

        // Track code blocks
        if (content.startsWith("```", i)) {
            inFencedCodeBlock = !inFencedCodeBlock
            markdownBuffer.append("```")
            i += 3
            continue
        }

        if (!inFencedCodeBlock && char == '`') {
            inInlineCode = !inInlineCode
            markdownBuffer.append(char)
            i++
            continue
        }

        if (!inFencedCodeBlock && !inInlineCode) {
            // Check for display math \[ ... \]
            if (content.startsWith(DISPLAY_MATH_BRACKET_OPEN, i)) {
                val startIndex = i + DISPLAY_MATH_BRACKET_OPEN.length
                val endIndex = content.indexOf(DISPLAY_MATH_BRACKET_CLOSE, startIndex)
                if (endIndex != -1) {
                    val expression = content.substring(startIndex, endIndex).trim()
                    if (expression.isNotEmpty()) {
                        flushMarkdownBuffer()
                        blocks.add(ChatMarkdownBlock.DisplayMath(expression))
                        i = endIndex + DISPLAY_MATH_BRACKET_CLOSE.length
                        continue
                    }
                }
            }

            // Check for display math $$ ... $$
            if (content.startsWith(DISPLAY_MATH_DOUBLE_DOLLAR, i)) {
                val startIndex = i + DISPLAY_MATH_DOUBLE_DOLLAR.length
                val endIndex = content.indexOf(DISPLAY_MATH_DOUBLE_DOLLAR, startIndex)
                if (endIndex != -1) {
                    val expression = content.substring(startIndex, endIndex).trim()
                    if (expression.isNotEmpty()) {
                        flushMarkdownBuffer()
                        blocks.add(ChatMarkdownBlock.DisplayMath(expression))
                        i = endIndex + DISPLAY_MATH_DOUBLE_DOLLAR.length
                        continue
                    }
                }
            }
        }

        markdownBuffer.append(char)
        i++
    }

    flushMarkdownBuffer()

    return ParsedChatMarkdown(blocks, inlineMath)
}
