package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceMicrophoneAction

/** Both voice surfaces use the accepted microphone action, including queued replies. */
@Composable
internal fun VoiceMicrophoneButton(
    action: VoiceMicrophoneAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    iconSize: Dp = 24.dp,
    stopLabel: String = "Stop",
    motionEnabled: Boolean = voiceMotionEnabled()
) {
    val pulse by if (enabled && action == VoiceMicrophoneAction.FINISH_CAPTURE && motionEnabled) {
        rememberInfiniteTransition(label = "Listening microphone").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "Microphone highlight"
        )
    } else {
        rememberUpdatedState(1f)
    }
    val colors = MaterialTheme.colorScheme
    val container = voiceMicrophoneContainerColor(action, colors.surfaceContainerHigh, colors.primary, pulse)
    // Only the background breathes; the microphone stays opaque and readable.
    val foreground = if (action == VoiceMicrophoneAction.LISTEN) colors.onSurfaceVariant else colors.onPrimary
    val (icon, description) = when (action) {
        VoiceMicrophoneAction.LISTEN -> Icons.Rounded.Mic to "Start listening"
        VoiceMicrophoneAction.FINISH_CAPTURE -> Icons.Rounded.Mic to "Finish speaking"
        VoiceMicrophoneAction.STOP -> Icons.Rounded.Stop to stopLabel
    }
    if (compact) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = foreground)
        ) { Icon(icon, description, Modifier.size(iconSize)) }
    } else {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = foreground)
        ) { Icon(icon, description, Modifier.size(iconSize)) }
    }
}

/** A capture remains visibly active at the quiet end of its pulse. Stop never fades. */
internal fun voiceMicrophoneContainerColor(action: VoiceMicrophoneAction, neutral: Color, highlight: Color, pulse: Float): Color = when (action) {
    VoiceMicrophoneAction.LISTEN -> neutral
    VoiceMicrophoneAction.STOP -> highlight
    VoiceMicrophoneAction.FINISH_CAPTURE -> lerp(neutral, highlight, .78f + .22f * (pulse.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f))
}
