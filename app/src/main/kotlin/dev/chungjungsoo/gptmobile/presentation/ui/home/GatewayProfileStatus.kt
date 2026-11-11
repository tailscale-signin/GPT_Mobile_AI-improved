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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalConfiguration
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
import java.text.DateFormat
import java.util.Date

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
    val previous = availability.lastObservedStatus?.takeIf {
        availability.status == GatewayAvailabilityStatus.CHECKING && availability.lastObservedAtMillis != null
    }
    val label = if (previous != null) {
        val locale = LocalConfiguration.current.locales[0]
        val checkedAt = remember(availability.lastObservedAtMillis, locale) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(requireNotNull(availability.lastObservedAtMillis)))
        }
        stringResource(R.string.gateway_availability_last_checked, checkedAt, gatewayAvailabilityLabel(previous))
    } else {
        gatewayAvailabilityLabel(availability.status)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (availability.isOnline) {
                val green = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF81C784) else Color(0xFF1B5E20)
                Box(Modifier.size(8.dp).background(green, CircleShape).testTag("gateway_online_dot"))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!availability.isOnline) {
            availability.recoveryAction?.let { action ->
                Text(gatewayRecoveryLabel(action), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("gateway_recovery_hint"))
            }
        }
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

@Composable
internal fun gatewayRecoveryLabel(action: String): String = stringResource(
    when (action) {
        "wait_for_model" -> R.string.gateway_recovery_wait_model
        "load_selected_model" -> R.string.gateway_recovery_load_model
        "select_available_model" -> R.string.gateway_recovery_select_model
        "restore_backend" -> R.string.gateway_recovery_backend
        "check_backend_access" -> R.string.gateway_recovery_access
        "restore_storage" -> R.string.gateway_recovery_storage
        "wait_for_gateway" -> R.string.gateway_recovery_draining
        "repair_configuration" -> R.string.gateway_recovery_configuration
        "authorize" -> R.string.gateway_recovery_authorize
        "reconcile" -> R.string.gateway_recovery_reconcile
        "verify_acceptance" -> R.string.gateway_recovery_verify
        else -> R.string.gateway_recovery_inspect
    }
)
