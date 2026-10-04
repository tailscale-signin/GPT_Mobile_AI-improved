package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

internal val DebugMemoryPink = Color(0xFFFF5CAA)
private val ReviewerYellow = Color(0xFFFFEA00)
private val reviewerScorePattern = Regex("(?:Reviewer Score\\s*:\\s*|\\\"review_score\\\"\\s*:\\s*|score=)(\\d{1,3})(?:/100)?", RegexOption.IGNORE_CASE)

@Composable
internal fun ReviewerDebugText(content: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodySmall
    val text = remember(content, style.fontSize) {
        buildAnnotatedString {
            append(content)
            reviewerScorePattern.findAll(content).forEach { match ->
                val score = match.groups[1] ?: return@forEach
                addStyle(SpanStyle(fontSize = style.fontSize * 3, fontWeight = FontWeight.Bold), score.range.first, score.range.last + 1)
            }
        }
    }
    Text(text, modifier, color = ReviewerYellow, style = style)
}
