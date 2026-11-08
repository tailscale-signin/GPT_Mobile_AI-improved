package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.view.accessibility.AccessibilityManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus

/** Accessibility services can own virtual focus without touch exploration; keep their rows stable. */
@Composable
internal fun rememberProfileAccessibilityInteraction(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled = it }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun rememberGatewayNameOpacity(active: Boolean, minimumOpacity: Float = GATEWAY_NAME_MINIMUM_OPACITY): State<Float> {
    val reduced by FrameEffectPressure.guard.reduced.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return if (active && LocalWindowInfo.current.isWindowFocused && !reduced && (durationScale?.scaleFactor ?: 1f) > 0f && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        rememberInfiniteTransition(label = "gateway_profile_name").animateFloat(
            initialValue = 1f,
            targetValue = minimumOpacity,
            animationSpec = infiniteRepeatable(tween(GATEWAY_NAME_FADE_HALF_CYCLE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "name_opacity"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
}

internal val GatewayNameOpacityKey = SemanticsPropertyKey<Float>("GatewayNameOpacity")
internal var SemanticsPropertyReceiver.gatewayNameOpacity by GatewayNameOpacityKey

@Composable
internal fun GatewayAvailabilityBadge(availability: GatewayAvailability, profileUid: String) {
    val online = availability.status in setOf(GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val background = if (online) {
        if (dark) Color(0xFF123B21) else Color(0xFFE3F4E8)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val foreground = if (online) {
        if (dark) Color(0xFF9CE5AE) else Color(0xFF146C2E)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (availability.status) {
        GatewayAvailabilityStatus.ONLINE -> "Online"
        GatewayAvailabilityStatus.BUSY_QUEUE -> "Online · can queue"
        GatewayAvailabilityStatus.MODEL_UNAVAILABLE -> "Connected · model unavailable"
        GatewayAvailabilityStatus.CHECKING -> "Checking connection"
        GatewayAvailabilityStatus.ATTENTION -> "Connection needs attention"
        GatewayAvailabilityStatus.OFFLINE -> "Offline"
    }
    Surface(
        modifier = Modifier.testTag("gateway_availability_$profileUid").semantics { stateDescription = availability.message },
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = foreground
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Decorative and steady: the label alone supplies the accessible status.
            if (online) Box(Modifier.size(6.dp).background(foreground, CircleShape).testTag("gateway_online_dot_$profileUid"))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
