package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus

@Composable
internal fun gatewayAvailabilityLabel(status: GatewayAvailabilityStatus): String = stringResource(
    when (status) {
        GatewayAvailabilityStatus.ONLINE -> R.string.gateway_availability_online
        GatewayAvailabilityStatus.BUSY_QUEUE -> R.string.gateway_availability_busy_queue
        GatewayAvailabilityStatus.MODEL_LOADING -> R.string.gateway_availability_model_loading
        GatewayAvailabilityStatus.CHECKING -> R.string.gateway_availability_checking
        GatewayAvailabilityStatus.MODEL_UNAVAILABLE -> R.string.gateway_availability_model_unavailable
        GatewayAvailabilityStatus.NOT_READY -> R.string.gateway_availability_not_ready
        GatewayAvailabilityStatus.DRAINING -> R.string.gateway_availability_draining
        GatewayAvailabilityStatus.ACCESS_DENIED -> R.string.gateway_availability_access_denied
        GatewayAvailabilityStatus.INCOMPATIBLE -> R.string.gateway_availability_incompatible
        GatewayAvailabilityStatus.OFFLINE -> R.string.gateway_availability_offline
        GatewayAvailabilityStatus.CONFIGURATION_REQUIRED -> R.string.gateway_availability_configuration
        GatewayAvailabilityStatus.NOT_GATEWAY -> R.string.gateway_availability_not_gateway
    }
)

@Composable
internal fun GatewayProfileStatus(availability: GatewayAvailability, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (availability.isOnline) {
            val green = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF81C784) else Color(0xFF1B5E20)
            Box(Modifier.size(8.dp).background(green, CircleShape).testTag("gateway_online_dot"))
        }
        Text(
            gatewayAvailabilityLabel(availability.status),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A six-second complete cycle. No animation frame is exposed as an accessibility update. */
@Composable
internal fun rememberGatewayNameAlpha(active: Boolean, background: Color, foreground: Color = MaterialTheme.colorScheme.primary, minimumOpacity: Float = GATEWAY_NAME_MINIMUM_OPACITY): State<Float> {
    val reduced by FrameEffectPressure.guard.reduced.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val preservesContrast = gatewayNamePulsePreservesContrast(foreground, background, minimumOpacity)
    val animate = active &&
        LocalWindowInfo.current.isWindowFocused &&
        minimumOpacity < 1f &&
        !reduced &&
        preservesContrast &&
        ValueAnimator.areAnimatorsEnabled() &&
        (durationScale?.scaleFactor ?: 1f) > 0f &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    return if (animate) {
        rememberInfiniteTransition(label = "gateway_name").animateFloat(
            initialValue = 1f,
            targetValue = minimumOpacity,
            animationSpec = infiniteRepeatable(tween(GATEWAY_NAME_FADE_HALF_CYCLE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "gateway_name_alpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
}

/** Keep theme colours accessible on selected cards as well as at the faintest pulse frame. */
internal fun gatewayProfileNameColor(preferred: Color, background: Color, fallback: Color): Color =
    if (gatewayNamePulsePreservesContrast(preferred, background)) preferred else fallback

/** Dynamic theme colours that would lose normal-text contrast retain a static themed name. */
internal fun gatewayNamePulsePreservesContrast(foreground: Color, background: Color, minimumOpacity: Float = GATEWAY_NAME_MINIMUM_OPACITY): Boolean {
    val text = foreground.copy(alpha = foreground.alpha * minimumOpacity).compositeOver(background).luminance()
    val surface = background.luminance()
    return (maxOf(text, surface) + .05f) / (minOf(text, surface) + .05f) >= 4.5f
}
