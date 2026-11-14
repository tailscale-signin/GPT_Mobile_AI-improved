package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure
import dev.chungjungsoo.gptmobile.data.model.NewChatType
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

/** Filled unread replies stay still; only actual work pulses. */
@Composable
internal fun GatewayConversationIcon(
    working: Boolean,
    unread: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    conversationType: NewChatType? = null
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val reduced by FrameEffectPressure.guard.reduced.collectAsStateWithLifecycle()
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val pulse = working && animate && !reduced && scale > 0f && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "gateway_work")
        val value by transition.animateFloat(1f, 0.45f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gateway_work_alpha")
        value
    } else {
        1f
    }
    val status = when {
        working -> "working"
        unread -> "unread reply"
        else -> "read"
    }
    if (conversationType == null) {
        Icon(
            if (working || unread) Icons.Rounded.Cloud else Icons.Rounded.CloudQueue,
            "Gateway · $status",
            modifier.size(24.dp).testTag("gateway_conversation_icon").graphicsLayer { this.alpha = alpha },
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
        // The Gateway tab identifies the actual chat mode inside its conversation bubble.
        // Other destinations keep the familiar gateway connection icon.
        Box(
            modifier.size(40.dp).testTag("gateway_conversation_mode_${conversationType.name}")
                .semantics { contentDescription = "Gateway · ${conversationType.title} · $status" }
                .graphicsLayer { this.alpha = alpha },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.ChatBubble, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (unread) 0.18f else 0.06f))
            Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
            Icon(conversationTypeIcon(conversationType, unread), null, Modifier.size(20.dp).offset(y = (-2).dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
