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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun voiceMotionEnabled(): Boolean {
    val reduced by FrameEffectPressure.guard.reduced.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return !reduced &&
        motionScale > 0f &&
        ValueAnimator.areAnimatorsEnabled() &&
        LocalWindowInfo.current.isWindowFocused &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED)
}

@Composable
internal fun VoiceWave(state: VoiceState, modifier: Modifier = Modifier) {
    val animated = voiceMotionEnabled()
    val listening = state.phase == VoicePhase.LISTENING
    val busy = state.phase in setOf(VoicePhase.STARTING, VoicePhase.FINALIZING, VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
    val orbitalPhase = if ((busy || listening) && animated) {
        rememberInfiniteTransition(label = "Voice state halo").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(if (listening) 6400 else 4800, easing = LinearEasing), RepeatMode.Restart),
            label = "Voice state orbit"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    // Input bars use measured microphone energy. Orbital motion represents a session state;
    // it does not invent output amplitude or speech timing that the engine did not report.
    val level by animateFloatAsState(
        if (listening && state.hasAudioLevel) state.level.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f else 0f,
        animationSpec = if (animated) tween(120) else snap(),
        label = "Measured microphone level"
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val accent = if (state.phase == VoicePhase.ERROR) error else primary
    val orbitColors = listOf(accent, secondary, tertiary)
    Canvas(modifier.semantics { contentDescription = if (listening) "Microphone input level" else "Voice activity" }) {
        // Reading the phase here redraws the stage without recomposing its controls each frame.
        val phase = orbitalPhase.value
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width * .29f, size.height * .36f)
        val angle = phase * Math.PI * 2
        val active = busy || listening
        val breath = if (active) .14f + .025f * sin(angle).toFloat() + level * .06f else .09f
        val glowRadius = radius * 2.05f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = breath), accent.copy(alpha = 0f)), center, glowRadius), glowRadius, center)
        val coreRadius = radius * (.69f + level * .07f)
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = .2f + level * .12f), secondary.copy(alpha = .08f), accent.copy(alpha = .025f)),
                center = center - Offset(coreRadius * .3f, coreRadius * .3f),
                radius = coreRadius * 1.5f
            ),
            coreRadius,
            center
        )
        drawCircle(accent.copy(alpha = .14f), radius, center, style = Stroke(1.dp.toPx()))
        drawCircle(secondary.copy(alpha = .09f), radius * 1.25f, center, style = Stroke(1.dp.toPx()))
        repeat(3) { orbit ->
            val orbitRadius = radius * (.86f + orbit * .15f)
            drawArc(
                color = orbitColors[orbit].copy(alpha = if (active) .65f - orbit * .14f else .25f - orbit * .04f),
                startAngle = phase * (if (orbit == 1) -360f else 360f) - 90f + orbit * 120f,
                sweepAngle = if (state.phase == VoicePhase.SPEAKING) 112f else 70f + orbit * 18f,
                useCenter = false,
                topLeft = center - Offset(orbitRadius, orbitRadius),
                size = Size(orbitRadius * 2, orbitRadius * 2),
                style = Stroke((if (orbit == 0) 2.5.dp else 1.5.dp).toPx(), cap = StrokeCap.Round)
            )
        }
        if (listening) {
            val count = 17
            val gap = radius * 1.2f / count
            val width = gap * .52f
            repeat(count) { index ->
                val envelope = sin(Math.PI * (index + 1) / (count + 1)).toFloat()
                val shape = .55f + .45f * abs(sin(index * .71f))
                val height = 4.dp.toPx() + radius * 1.05f * level * envelope * shape
                val x = center.x - radius * .6f + (index + .5f) * gap - width / 2
                drawRoundRect(accent, Offset(x, center.y - height / 2), Size(width, height), CornerRadius(width / 2), alpha = .92f)
            }
        } else {
            drawCircle(accent.copy(alpha = if (busy) .6f else .3f), radius * .11f, center)
            if (busy) {
                repeat(2) { index ->
                    val orbitAngle = angle + index * Math.PI
                    val point = center + Offset(cos(orbitAngle).toFloat(), sin(orbitAngle).toFloat()) * radius * .45f
                    drawCircle(if (index == 0) accent else tertiary, radius * .045f, point, alpha = .7f)
                }
            }
        }
    }
}
