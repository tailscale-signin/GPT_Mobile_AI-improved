package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun VoiceWave(state: VoiceState, modifier: Modifier = Modifier) {
    val reduced by FrameEffectPressure.guard.reduced.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val animated = !reduced &&
        motionScale > 0f &&
        ValueAnimator.areAnimatorsEnabled() &&
        LocalWindowInfo.current.isWindowFocused &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val listening = state.phase == VoicePhase.LISTENING
    val busy = state.phase in setOf(VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
    val phase = if (busy && animated) {
        rememberInfiniteTransition(label = "Voice state halo").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
            label = "Voice state orbit"
        ).value
    } else {
        0f
    }
    // A single measured input level shapes the bars. Output has no amplitude telemetry:
    // its halo is a state effect, and never poses as an audio waveform.
    val level by animateFloatAsState(
        if (listening && state.hasAudioLevel) state.level.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f else 0f,
        animationSpec = if (animated) tween(120) else snap(),
        label = "Measured microphone level"
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier.semantics { contentDescription = if (listening) "Microphone input level" else "Voice activity" }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.width * .46f
        val breath = if (busy) .11f + .025f * sin(phase * Math.PI * 2).toFloat() else .07f + level * .06f
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = breath), primary.copy(alpha = 0f)), center, radius), radius, center)
        if (busy) {
            val orbit = Size(size.width * .56f, size.height * .76f)
            drawArc(
                secondary.copy(alpha = .35f),
                startAngle = phase * 360f - 90f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(center.x - orbit.width / 2, center.y - orbit.height / 2),
                size = orbit,
                style = Stroke(1.5.dp.toPx())
            )
        }
        val count = 33
        val gap = size.width * .78f / count
        val width = gap * .5f
        repeat(count) { index ->
            val envelope = sin(Math.PI * (index + 1) / (count + 1)).toFloat()
            val shape = .55f + .45f * abs(sin(index * .71f))
            val height = 6.dp.toPx() + size.height * .7f * level * envelope * shape
            val x = size.width * .11f + index * gap
            drawRoundRect(primary, Offset(x, center.y - height / 2), Size(width, height), CornerRadius(width / 2), alpha = if (listening || busy) .9f else .4f)
        }
    }
}
