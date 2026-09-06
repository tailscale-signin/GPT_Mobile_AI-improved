package dev.melo.gptmobile.improved.presentation.chat.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.util.regex.Pattern

object ChatMarkdownParser {

    private val BOLD_PATTERN = Pattern.compile("\\*\\*(.*?)\\*\\*|__(.*?)__")
    private val ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.*?)(?<!_)_(?!_)")
    private val STRIKETHROUGH_PATTERN = Pattern.compile("~~(.*?)~~")
    private val INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`")

    fun parseMarkdown(
        text: String,
        codeBackgroundColor: Color = Color(0x1F000000),
        codeTextColor: Color = Color.Unspecified
    ): AnnotatedString {
        return buildAnnotatedString {
            append(text)

            applyRegexStyle(BOLD_PATTERN, SpanStyle(fontWeight = FontWeight.Bold))
            applyRegexStyle(ITALIC_PATTERN, SpanStyle(fontStyle = FontStyle.Italic))
            applyRegexStyle(STRIKETHROUGH_PATTERN, SpanStyle(textDecoration = TextDecoration.LineThrough))
            applyRegexStyle(
                INLINE_CODE_PATTERN,
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackgroundColor,
                    color = codeTextColor
                )
            )
        }
    }

    private fun AnnotatedString.Builder.applyRegexStyle(pattern: Pattern, style: SpanStyle) {
        val matcher = pattern.matcher(this.toAnnotatedString().text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            addStyle(style, start, end)
        }
    }
}
