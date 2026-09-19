package dev.chungjungsoo.gptmobile.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reusable SwipeableChatRow component supporting swipe-to-archive (StartToEnd),
 * swipe-to-delete (EndToStart), 1-second long-press for pin/favorite toggles,
 * and draft preview badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableChatRow(
    chatRoom: ChatRoomV2,
    idx: Int,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSearchMode: Boolean,
    isGenerating: Boolean,
    usingPlatform: String,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteRequest: () -> Unit,
    onOneSecondHold: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchiveClick()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteRequest()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    if (isSelectionMode || isSearchMode) {
        ChatRowContent(
            chatRoom = chatRoom,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            isGenerating = isGenerating,
            usingPlatform = usingPlatform,
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
            onOneSecondHold = onOneSecondHold,
            onArchiveClick = onArchiveClick,
            showArchiveButton = !isSelectionMode && !isSearchMode,
            modifier = modifier
        )
    } else {
        SwipeToDismissBox(
            state = dismissState,
            modifier = modifier,
            backgroundContent = {
                val color by animateColorAsState(
                    when (dismissState.targetValue) {
                        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                        SwipeToDismissBoxValue.Settled -> Color.Transparent
                    },
                    label = "swipe_background_color"
                )
                val alignment = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    SwipeToDismissBoxValue.Settled -> Alignment.Center
                }
                val icon = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Archive
                    SwipeToDismissBoxValue.EndToStart -> Icons.Outlined.Delete
                    SwipeToDismissBoxValue.Settled -> Icons.Default.Archive
                }
                val iconTint = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 24.dp),
                    contentAlignment = alignment
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        ) {
            ChatRowContent(
                chatRoom = chatRoom,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isGenerating = isGenerating,
                usingPlatform = usingPlatform,
                onItemClick = onItemClick,
                onItemLongClick = onItemLongClick,
                onOneSecondHold = onOneSecondHold,
                onArchiveClick = onArchiveClick,
                showArchiveButton = true
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowContent(
    chatRoom: ChatRoomV2,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isGenerating: Boolean,
    usingPlatform: String,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onOneSecondHold: (() -> Unit)?,
    onArchiveClick: () -> Unit,
    showArchiveButton: Boolean,
    modifier: Modifier = Modifier
) {
    val clickModifier = if (onOneSecondHold != null) {
        Modifier.pointerInput(chatRoom.id) {
            detectTapGestures(
                onTap = { onItemClick() },
                onLongPress = { onItemLongClick() },
                onPress = {
                    coroutineScope {
                        val job = launch {
                            delay(1000L)
                            onOneSecondHold()
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            job.cancel()
                        }
                    }
                }
            )
        }
    } else {
        Modifier.combinedClickable(
            onLongClick = onItemLongClick,
            onClick = onItemClick
        )
    }

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = 8.dp),
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (chatRoom.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.pinned_chat),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = chatRoom.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        leadingContent = {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onItemClick() }
                )
            } else if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_rounded_chat),
                    contentDescription = stringResource(R.string.chat_icon)
                )
            }
        },
        supportingContent = {
            if (!chatRoom.draftText.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0x33FFC107),
                        border = BorderStroke(1.dp, Color(0xFFFFC107).copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "DRAFT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color(0xFFFFB300),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = chatRoom.draftText,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.using_certain_platform, usingPlatform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        },
        trailingContent = {
            if (showArchiveButton) {
                IconButton(onClick = onArchiveClick) {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = stringResource(R.string.archive_chat),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    )
}
