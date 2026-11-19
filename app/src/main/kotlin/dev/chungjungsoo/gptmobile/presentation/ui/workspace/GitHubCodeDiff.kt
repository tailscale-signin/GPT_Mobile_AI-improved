package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

/** The original +/- markers remain readable without relying on color perception. */
@Composable
internal fun GitHubCodeDiff(diff: String) {
    val colors = MaterialTheme.colorScheme
    val pages = remember(diff) {
        buildList {
            var start = 0
            while (start < diff.length) {
                var end = (start + 6000).coerceAtMost(diff.length)
                if (end < diff.length) {
                    val newline = diff.lastIndexOf('\n', end - 1)
                    if (newline >= start) end = newline + 1 else if (diff[end - 1].isHighSurrogate()) end--
                }
                add(diff.substring(start, end))
                start = end
            }
        }
    }
    var page by remember(diff) { mutableIntStateOf(0) }
    if (pages.size > 1) {
        Row {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous page") }
            Text("${page + 1} / ${pages.size}")
            TextButton(onClick = { page++ }, enabled = page < pages.lastIndex) { Text("Next page") }
        }
    }
    val visible = pages.getOrNull(page).orEmpty()
    val highlighted = remember(visible, colors) {
        buildAnnotatedString {
            visible.lineSequence().forEachIndexed { index, line ->
                val style = when {
                    index < 2 && (line.startsWith("--- ") || line.startsWith("+++ ")) -> SpanStyle(color = colors.primary)
                    line.startsWith("@@") -> SpanStyle(color = colors.secondary)
                    line.startsWith('+') -> SpanStyle(color = colors.onTertiaryContainer, background = colors.tertiaryContainer)
                    line.startsWith('-') -> SpanStyle(color = colors.onErrorContainer, background = colors.errorContainer)
                    else -> SpanStyle(color = colors.onSurface)
                }
                if (index > 0) append('\n')
                withStyle(style) { append(line) }
            }
        }
    }
    SelectionContainer {
        Text(highlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("github_review_diff"))
    }
}
