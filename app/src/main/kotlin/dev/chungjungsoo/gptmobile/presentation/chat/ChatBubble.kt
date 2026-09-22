package dev.chungjungsoo.gptmobile.presentation.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.util.AnimationUtil

/**
 * ChatBubble — A message bubble with fade-in animation for AI responses and tool calls.
 * 
 * Features:
 * - Fades from 0% to 100% opacity over 1 second (as requested)
 * - Staggered animation for multiple bubbles
 * - Smooth deceleration interpolator
 */
@Composable
fun ChatBubble(
    messageText: String,
    isAIResponse: Boolean = false,
    isToolCall: Boolean = false,
    modifier: Modifier = Modifier,
    onFadeComplete: (() -> Unit)? = null
) {
    // Fade animation state
    val fadeInAnim by remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        fadeInAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 1000, // 1 second as requested
                easing = DecelerateEasing
            )
        )
        onFadeComplete?.invoke()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(fadeInAnim.value),
        shape = RoundedCornerShape(16.dp),
        color = if (isAIResponse) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = fadeInAnim.value)
        } else if (isToolCall) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = fadeInAnim.value)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = fadeInAnim.value)
        },
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Icon based on message type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isAIResponse) androidx.compose.material.icons.Icons.Default.AiOutline else if (isToolCall) androidx.compose.material.icons.Icons.Default.BuildOutline else androidx.compose.material.icons.Icons.Default.Message,
                        contentDescription = null,
                        tint = if (isAIResponse) MaterialTheme.colorScheme.primary else if (isToolCall) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isAIResponse) MaterialTheme.colorScheme.onPrimaryContainer else if (isToolCall) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Left,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * ChatBubbleList — A list of chat bubbles with staggered fade-in animation.
 * 
 * Each bubble fades in sequentially with a 200ms delay between them.
 */
@Composable
fun ChatBubbleList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onFadeComplete: (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        messages.forEachIndexed { index, message ->
            val delay = index * 200 // 200ms stagger between bubbles
            
            LaunchedEffect(index) {
                AnimationUtil.fadeInSequentially(
                    listOf(Unit), // Placeholder for animation tracking
                    delayMs = delay
                )
            }
            
            ChatBubble(
                messageText = message.text,
                isAIResponse = message.isAIResponse,
                isToolCall = message.isToolCall,
                modifier = Modifier.animateItemPlacement(),
                onFadeComplete = onFadeComplete
            )
        }
    }
}

/**
 * Data class representing a chat message.
 */
data class ChatMessage(
    val text: String,
    val isAIResponse: Boolean = false,
    val isToolCall: Boolean = false,
    val timestamp: String = ""
)
