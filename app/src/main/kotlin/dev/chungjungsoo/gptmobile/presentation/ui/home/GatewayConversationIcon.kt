package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

/** Filled unread replies stay still; only actual work pulses. */
@Composable
internal fun GatewayConversationIcon(working: Boolean, unread: Boolean, modifier: Modifier = Modifier, animate: Boolean = true) {
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
    Icon(
        if (working || unread) Icons.Rounded.Cloud else Icons.Rounded.CloudQueue,
        "Gateway · " + when {
            working -> "working"
            unread -> "unread reply"
            else -> "read"
        },
        modifier.size(24.dp).testTag("gateway_conversation_icon").graphicsLayer { this.alpha = alpha },
        tint = MaterialTheme.colorScheme.primary
    )
}
