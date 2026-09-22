package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.DecelerateInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.*

/**
 * Formats message timestamp for display
 */
fun formatMessageTimestamp(timestampMillis: Long?): String {
    return if (timestampMillis != null) {
        val date = java.util.Date(timestampMillis)
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(date)
    } else {
        ""
    }
}

/**
 * Determines if continue prompt should be shown
 */
fun shouldShowContinuePrompt(text: String, isLoading: Boolean, isLastMessage: Boolean = false): Boolean {
    return !isLoading && text.isNotBlank() && isLastMessage
}

/**
 * User chat bubble component
 */
@Composable
fun UserChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    timestamp: Long? = null,
    files: List<String> = emptyList(),
    hasDetails: Boolean = false,
    areDetailsVisible: Boolean = false,
    onToggleDetails: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B82F6))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatMessageTimestamp(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        }
    }
}

/**
 * Opponent chat bubble with full functionality
 */
@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canRetry: Boolean = false,
    isLoading: Boolean = false,
    isError: Boolean = false,
    text: String = "",
    timestamp: Long? = null,
    thoughts: String = "",
    timeline: List<AssistantTimelineItem> = emptyList(),
    attachments: List<String> = emptyList(),
    agentRun: AgentRun? = null,
    runNotices: List<ChatRunNotice> = emptyList(),
    toolEvents: List<ToolEvent> = emptyList(),
    contentIdentity: Any = "",
    canEdit: Boolean = false,
    isFavorite: Boolean = false,
    debugMode: Boolean = false,
    revisionIndexLabel: String? = null,
    canShowPreviousRevision: Boolean = false,
    canShowNextRevision: Boolean = false,
    isUserTyping: Boolean = false,
    isLastMessage: Boolean = false,
    onCopyClick: () -> Unit = {},
    onSelectClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onFavoriteLongPress: () -> Unit = {},
    onShowPreviousRevision: () -> Unit = {},
    onShowNextRevision: () -> Unit = {},
    onContinueClick: (() -> Unit)? = null,
    onActionClick: ((String) -> Unit)? = null
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(durationMillis = 1000L, easing = DecelerateInterpolator()))
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Black
                        )
                    }
                }
                if (timestamp != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatMessageTimestamp(timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

/**
 * Details button for chat messages
 */
@Composable
fun DetailsButton(
    isVisible: Boolean = false,
    isEnabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    if (isVisible) {
        IconButton(
            onClick = onClick,
            enabled = isEnabled
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Details"
            )
        }
    }
}

/**
 * Message file thumbnail row
 */
@Composable
fun MessageFileThumbnailRow(
    files: List<String>,
    modifier: Modifier = Modifier,
    usePrimaryColors: Boolean = false
) {
    if (files.isNotEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            files.forEach { file ->
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray)
                )
            }
        }
    }
}
