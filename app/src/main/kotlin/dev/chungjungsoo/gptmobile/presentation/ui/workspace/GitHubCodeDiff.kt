package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val highlighted = remember(diff, colors) {
        buildAnnotatedString {
            diff.lineSequence().forEachIndexed { index, line ->
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
