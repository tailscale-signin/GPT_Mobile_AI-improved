package dev.chungjungsoo.gptmobile.presentation.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * SwipeableChatRow — A chat list row that supports:
 * - Swipe RIGHT to reveal Archive action
 * - Swipe LEFT to reveal Delete + Pin actions
 * - Staggered icon reveal animation
 * - Spring-based snap-back
 * - Haptic feedback on trigger threshold
 */
@Composable
fun SwipeableChatRow(
    chatId: Int,
    chatTitle: String,
    lastMessage: String,
    timestamp: String,
    isPinned: Boolean,
    onArchive: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onPin: (Int) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val swipeOffset = remember { Animatable(0f) }
    val maxSwipe = 200f
    val triggerThreshold = 80f  // 40% of maxSwipe
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Background action panels
        SwipeActionBackground(
            swipeOffset = swipeOffset.value,
            maxSwipe = maxSwipe
        )

        // Foreground row content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = when {
                                swipeOffset.value > triggerThreshold -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onArchive(chatId)
                                    0f
                                }
                                swipeOffset.value < -triggerThreshold -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDelete(chatId)
                                    0f
                                }
                                else -> 0f
                            }
                            hasTriggeredHaptic = false
                            // Spring animation back to target
                            launch {
                                swipeOffset.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                        },
                        onDragCancel = {
                            hasTriggeredHaptic = false
                            launch {
                                swipeOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            launch {
                                val newValue = (swipeOffset.value + dragAmount)
                                    .coerceIn(-maxSwipe, maxSwipe)
                                swipeOffset.snapTo(newValue)

                                // Haptic feedback at threshold
                                if (!hasTriggeredHaptic &&
                                    abs(newValue) >= triggerThreshold
                                ) {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.LongPress
                                    )
                                    hasTriggeredHaptic = true
                                }
                            }
                        }
                    )
                }
                .zIndex(1f)
        ) {
            ChatRowContent(
                chatId = chatId,
                chatTitle = chatTitle,
                lastMessage = lastMessage,
                timestamp = timestamp,
                isPinned = isPinned,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun SwipeActionBackground(
    swipeOffset: Float,
    maxSwipe: Float
) {
    val progress = (abs(swipeOffset) / maxSwipe).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side (revealed on swipe right) — Archive
        if (swipeOffset > 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Color(0xFF4CAF50).copy(alpha = progress),
                        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                SwipeActionButton(
                    icon = Icons.Default.Archive,
                    label = "Archive",
                    color = Color.White,
                    progress = progress,
                    delay = 0
                )
            }
        }

        // Right side (revealed on swipe left) — Delete + Pin
        if (swipeOffset < 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Color(0xFFF44336).copy(alpha = progress),
                        RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SwipeActionButton(
                        icon = Icons.Default.PushPin,
                        label = "Pin",
                        color = Color.White,
                        progress = progress,
                        delay = 50
                    )
                    SwipeActionButton(
                        icon = Icons.Default.Delete,
                        label = "Delete",
                        color = Color.White,
                        progress = progress,
                        delay = 100
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    progress: Float,
    delay: Int
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 200,
            delayMillis = delay
        ),
        label = "btn_progress"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = animatedProgress)
        )
    }
}

@Composable
private fun ChatRowContent(
    chatId: Int,
    chatTitle: String,
    lastMessage: String,
    timestamp: String,
    isPinned: Boolean,
    onClick: (Int) -> Unit
) {
    Surface(
        onClick = { onClick(chatId) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chatTitle.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chatTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = lastMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
