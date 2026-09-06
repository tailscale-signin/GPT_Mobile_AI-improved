package dev.melo.gptmobile.improved.presentation.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

@Composable
fun ChatMarkdown(
    content: String,
    modifier: Modifier = Modifier
) {
    val tokens = remember(content) { ChatMarkdownParser.parse(content) }

    val annotatedString = remember(tokens) {
        buildAnnotatedString {
            for (token in tokens) {
                when (token) {
                    is MarkdownToken.Text -> append(token.text)
                    is MarkdownToken.Bold -> {
                        val start = length
                        append(token.text)
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    }
                    is MarkdownToken.Italic -> {
                        val start = length
                        append(token.text)
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                    }
                    is MarkdownToken.Code -> {
                        val start = length
                        append(token.text)
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.2f)
                            ),
                            start,
                            length
                        )
                    }
                    is MarkdownToken.Strikethrough -> {
                        val start = length
                        append(token.text)
                        addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                    }
                    is MarkdownToken.CodeBlock -> {
                        val start = length
                        append("\n${token.code}\n")
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = androidx.compose.ui.graphics.Color.DarkGray.copy(alpha = 0.15f)
                            ),
                            start,
                            length
                        )
                    }
                }
            }
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth()
    )
}
