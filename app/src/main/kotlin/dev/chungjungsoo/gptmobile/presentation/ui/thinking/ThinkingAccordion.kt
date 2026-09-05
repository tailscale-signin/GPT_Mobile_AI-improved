package dev.chungjungsoo.gptmobile.presentation.ui.thinking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Parsed representation of content that may contain DeepSeek / Reasoner thinking tokens (<think>...</think>).
 */
data class ParsedReasoningContent(
    val thinking: String?,
    val displayContent: String,
    val isThinkingInProgress: Boolean = false
) {
    val thinkingText: String?
        get() = thinking

    val mainContent: String
        get() = displayContent

    val response: String
        get() = displayContent

    val hasThinking: Boolean
        get() = thinking != null

    val isThinking: Boolean
        get() = isThinkingInProgress

    val isStillThinking: Boolean
        get() = isThinkingInProgress
}

object ThinkingParser {
    private val THINK_REGEX = Regex("(?s)<think>(.*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)

    /**
     * Parses a raw model response into thought process and final response text.
     */
    fun parse(rawText: String): ParsedReasoningContent {
        if (!rawText.contains("<think>")) {
            return ParsedReasoningContent(
                thinking = null,
                displayContent = rawText,
                isThinkingInProgress = false
            )
        }

        val match = THINK_REGEX.find(rawText) ?: return ParsedReasoningContent(null, rawText, false)
        val thinkingPart = match.groupValues[1].trim()
        val isStillThinking = !rawText.contains("</think>")
        val mainText = rawText.replace(match.value, "").trim()

        return ParsedReasoningContent(
            thinking = thinkingPart,
            displayContent = mainText,
            isThinkingInProgress = isStillThinking
        )
    }

    /**
     * Alias for ChatBubble and legacy callers.
     */
    fun extractThinking(rawText: String): ParsedReasoningContent = parse(rawText)
}

/**
 * Collapsible accordion UI component for DeepSeek / Reasoner thinking process.
 */
@Composable
fun ThinkingAccordion(
    thinkingText: String,
    isThinking: Boolean = false,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initialExpanded) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isThinking) "💭 Thinking in progress..." else "💭 Thought Process",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse thoughts" else "Expand thoughts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
