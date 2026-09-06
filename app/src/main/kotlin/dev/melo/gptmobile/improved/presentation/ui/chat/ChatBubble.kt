package dev.melo.gptmobile.improved.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.domain.model.Message
import dev.melo.gptmobile.improved.domain.model.MessageRole
import dev.melo.gptmobile.improved.presentation.util.DateUtil

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: Message,
    onCopyClick: (String) -> Unit,
    onDeleteClick: (Message) -> Unit,
    onRegenerateClick: (() -> Unit)? = null,
    onEditClick: ((Message) -> Unit)? = null,
    onAttachmentClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM

    if (isSystem) {
        SystemMessageBubble(message = message, modifier = modifier)
        return
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onCopyClick(message.content) }
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Thinking block if available
                message.thinking?.let { thinkingContent ->
                    if (thinkingContent.isNotBlank()) {
                        ThinkingBlock(
                            thinkingContent = thinkingContent,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Tool traces if available
                if (message.toolTraces.isNotEmpty()) {
                    ToolTraceBlock(
                        traces = message.toolTraces,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Agent run status if available
                message.agentRunStatus?.let { runStatus ->
                    AgentRunStatusBlock(
                        runStatus = runStatus,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Main message content
                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                } else {
                    if (message.content.isBlank() && message.isStreaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = contentColor
                        )
                    } else {
                        ChatMarkdown(
                            content = message.content,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Timestamp and actions
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MessageTime(
                        timestamp = message.timestamp,
                        isUser = isUser
                    )
                }
            }
        }

        // Action row below the bubble
        BubbleActionRow(
            message = message,
            isUser = isUser,
            onCopyClick = onCopyClick,
            onDeleteClick = onDeleteClick,
            onRegenerateClick = onRegenerateClick,
            onEditClick = onEditClick
        )
    }
}

@Composable
private fun BubbleActionRow(
    message: Message,
    isUser: Boolean,
    onCopyClick: (String) -> Unit,
    onDeleteClick: (Message) -> Unit,
    onRegenerateClick: (() -> Unit)?,
    onEditClick: ((Message) -> Unit)?
) {
    Row(
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        IconButton(
            onClick = { onCopyClick(message.content) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_content_copy),
                contentDescription = stringResource(R.string.copy),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }

        if (isUser && onEditClick != null) {
            IconButton(
                onClick = { onEditClick(message) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }

        if (!isUser && onRegenerateClick != null) {
            IconButton(
                onClick = onRegenerateClick,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.regenerate),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }

        IconButton(
            onClick = { onDeleteClick(message) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SystemMessageBubble(
    message: Message,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            tonalElevation = 0.dp
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun MessageTime(
    timestamp: Long,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    Text(
        text = DateUtil.formatTime(timestamp),
        style = MaterialTheme.typography.labelSmall,
        color = if (isUser) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = modifier
    )
}
