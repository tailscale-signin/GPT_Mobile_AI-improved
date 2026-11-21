package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
internal fun VoiceWave(
    state: VoiceState,
    modifier: Modifier = Modifier,
    outputLevel: kotlinx.coroutines.flow.StateFlow<Float?>? = null,
    inputLevel: kotlinx.coroutines.flow.StateFlow<dev.chungjungsoo.gptmobile.data.voice.VoiceInputLevel>? = null
) {
    val animated = voiceMotionEnabled()
    val listening = state.phase == VoicePhase.LISTENING
    val speaking = state.phase == VoicePhase.SPEAKING
    val preparing = state.phase in setOf(VoicePhase.STARTING, VoicePhase.FINALIZING, VoicePhase.THINKING, VoicePhase.PREPARING)
    val playbackLevel = if (speaking) outputLevel?.collectAsStateWithLifecycle() else null
    val microphoneLevel = if (listening) inputLevel?.collectAsStateWithLifecycle() else null
    val geometry = remember { FloatArray(32) { index -> sin(Math.PI * (index + 1) / 33).toFloat() * (.5f + .5f * abs(sin(index * 1.17f))) } }
    val phase = if (preparing && animated) {
        rememberInfiniteTransition(label = "Voice preparation").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "Preparation pulse"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val colors = MaterialTheme.colorScheme
    val primary = if (state.phase == VoicePhase.ERROR) colors.error else colors.primary
    val secondary = colors.secondary
    Canvas(
        modifier.semantics {
            contentDescription = if (listening) {
                "Microphone level"
            } else if (speaking && playbackLevel != null) {
                "Playback level"
            } else {
                "Voice activity"
            }
        }
    ) {
        // Read fast State values in drawing, so amplitude updates never recompose the screen.
        val input = microphoneLevel?.value
        val output = playbackLevel?.value
        val level = when {
            listening -> if (input != null) {
                input.level
            } else if (state.hasAudioLevel) {
                state.level
            } else {
                0f
            }
            speaking && output != null -> kotlin.math.sqrt(output)
            else -> 0f
        }.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val count = 32
        val gap = size.width * .9f / count
        val width = gap * .53f
        val baseline = 4.dp.toPx()
        val breathing = if (preparing && animated) (.06f + .035f * sin(phase.value * 2f * Math.PI.toFloat())) else 0f
        repeat(count) { index ->
            val height = baseline + size.height * .86f * (level + breathing).coerceAtMost(1f) * geometry[index]
            val x = size.width * .05f + (index + .5f) * gap - width / 2
            drawRoundRect(if (index % 3 == 0) secondary else primary, Offset(x, (size.height - height) / 2), Size(width, height), CornerRadius(width / 2))
        }
    }
}
