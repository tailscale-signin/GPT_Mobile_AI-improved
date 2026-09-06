package dev.melo.gptmobile.improved.presentation.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.database.entity.AgentRun
import dev.melo.gptmobile.improved.data.database.entity.MessageAttachmentV2
import dev.melo.gptmobile.improved.data.database.entity.ToolEvent
import dev.melo.gptmobile.improved.data.model.Message
import dev.melo.gptmobile.improved.data.model.Sender

@Composable
fun ChatBubble(
    message: Message,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current

    if (message.sender == Sender.USER) {
        UserChatBubble(
            modifier = modifier,
            text = message.content,
            onLongPress = {
                clipboardManager.setText(AnnotatedString(message.content))
            }
        )
    } else {
        OpponentChatBubble(
            modifier = modifier,
            canRetry = message.failed,
            isLoading = message.content.isEmpty() && !message.failed,
            isError = message.failed,
            text = message.content,
            onCopyClick = {
                clipboardManager.setText(AnnotatedString(message.content))
            },
            onRetryClick = onRetry
        )
    }
}

@Composable
fun UserChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    files: List<String> = emptyList(),
    onLongPress: () -> Unit
) {
    Card(
        modifier = modifier
            .animateContentSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (files.isNotEmpty()) {
                AttachmentPreviewGrid(filePaths = files)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canEdit: Boolean = false,
    canRetry: Boolean = false,
    isLoading: Boolean = false,
    isError: Boolean = false,
    isFavorite: Boolean = false,
    text: String,
    thoughts: String = "",
    timeline: List<String> = emptyList(),
    attachments: List<String> = emptyList(),
    agentRun: AgentRun? = null,
    runNotices: List<String> = emptyList(),
    toolEvents: List<ToolEvent> = emptyList(),
    contentIdentity: String = "",
    revisionIndexLabel: String? = null,
    canShowPreviousRevision: Boolean = false,
    canShowNextRevision: Boolean = false,
    onCopyClick: () -> Unit = {},
    onSelectClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onShowPreviousRevision: () -> Unit = {},
    onShowNextRevision: () -> Unit = {}
) {
    Card(
        modifier = modifier.animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            RunNoticeChips(notices = runNotices)

            if (toolEvents.isNotEmpty()) {
                ToolTraceBlock(
                    events = toolEvents,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (thoughts.isNotBlank()) {
                ThinkingBlock(
                    thoughts = thoughts,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (attachments.isNotEmpty()) {
                AttachmentPreviewGrid(filePaths = attachments)
                Spacer(modifier = Modifier.height(8.dp))
            }

            key(contentIdentity) {
                ChatMarkdown(
                    markdown = text,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AgentRunStatusBlock(run = agentRun, modifier = Modifier.padding(top = 8.dp))

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp
                )
            }

            ChatActionRow(
                canEdit = canEdit,
                canRetry = canRetry,
                isFavorite = isFavorite,
                revisionIndexLabel = revisionIndexLabel,
                canShowPreviousRevision = canShowPreviousRevision,
                canShowNextRevision = canShowNextRevision,
                onCopyClick = onCopyClick,
                onSelectClick = onSelectClick,
                onFavoriteClick = onFavoriteClick,
                onRetryClick = onRetryClick,
                onEditClick = onEditClick,
                onShowPreviousRevision = onShowPreviousRevision,
                onShowNextRevision = onShowNextRevision
            )
        }
    }
}

@Composable
private fun ChatActionRow(
    canEdit: Boolean,
    canRetry: Boolean,
    isFavorite: Boolean,
    revisionIndexLabel: String?,
    canShowPreviousRevision: Boolean,
    canShowNextRevision: Boolean,
    onCopyClick: () -> Unit,
    onSelectClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onRetryClick: () -> Unit,
    onEditClick: () -> Unit,
    onShowPreviousRevision: () -> Unit,
    onShowNextRevision: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (revisionIndexLabel != null) {
            IconButton(
                onClick = onShowPreviousRevision,
                enabled = canShowPreviousRevision,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.previous_revision),
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = revisionIndexLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onShowNextRevision,
                enabled = canShowNextRevision,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.next_revision),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onCopyClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                contentDescription = stringResource(R.string.copy_text),
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = onFavoriteClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(R.string.favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        if (canEdit) {
            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (canRetry) {
            IconButton(
                onClick = onRetryClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentPreviewGrid(filePaths: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        filePaths.forEach { path ->
            AttachmentItem(filePath = path)
        }
    }
}

@Composable
internal fun AttachmentItem(filePath: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = filePath.substringAfterLast("/"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PlatformButton(
    isLoading: Boolean,
    name: String,
    selected: Boolean,
    onPlatformClick: () -> Unit
) {
    OutlinedButton(
        onClick = onPlatformClick,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        }
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text = name, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun GPTMobileIcon(loading: Boolean) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp
        )
    } else {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_launcher_foreground),
            contentDescription = "GPT Mobile",
            modifier = Modifier.size(28.dp)
        )
    }
}
