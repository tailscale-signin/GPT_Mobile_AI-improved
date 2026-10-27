package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal data class BubbleAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val invoke: () -> Unit)

/** Prefer the side opposite the thumb; clamp to the visible window on narrow screens. */
internal fun bubbleMenuOffset(anchor: IntRect, touch: IntOffset, window: IntSize, menu: IntSize, gap: Int, margin: Int): IntOffset {
    val x = anchor.left + touch.x
    val y = anchor.top + touch.y
    val right = x.toLong() + gap
    val left = x.toLong() - gap - menu.width
    val maxX = (window.width - menu.width - margin).coerceAtLeast(0)
    val maxY = (window.height - menu.height - margin).coerceAtLeast(0)
    val fitsRight = right + menu.width <= window.width - margin
    val fitsLeft = left >= margin
    val preferred = when {
        x < window.width / 2 && fitsRight -> right
        fitsLeft -> left
        fitsRight -> right
        else -> left
    }
    val above = y.toLong() - gap - menu.height
    val below = y.toLong() + gap
    val preferredY = when {
        fitsLeft || fitsRight -> y.toLong() - menu.height / 2
        above >= margin -> above
        below + menu.height <= window.height - margin -> below
        else -> y.toLong() - menu.height / 2
    }
    return IntOffset(
        preferred.coerceIn(minOf(margin, maxX).toLong(), maxX.toLong()).toInt(),
        preferredY.coerceIn(minOf(margin, maxY).toLong(), maxY.toLong()).toInt()
    )
}

/** Stable bubble identity keeps streaming deltas from restarting an in-progress hold. */
@Composable
internal fun BubbleActionHost(identity: Any, actions: List<BubbleAction>, content: @Composable () -> Unit) {
    var touch by remember(identity) { mutableStateOf<Offset?>(null) }
    val latestActions by rememberUpdatedState(actions)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    val shake = remember(identity) { Animatable(0f) }
    val amplitude = with(density) { 2.dp.toPx() }
    val menuLabel = stringResource(R.string.message_actions)

    fun open(position: Offset) {
        if (latestActions.none { it.enabled }) return
        touch = position
        // Respect the system's touch-feedback preference; waveform lasts exactly 500 ms.
        runCatching {
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                val vibrator = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibrator?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 70, 40, 70, 40, 70, 30, 70), -1)
                )
            }
        }
        scope.launch {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 500
                    -1f at 45
                    1f at 95
                    -0.85f at 150
                    0.85f at 210
                    -0.6f at 275
                    0.6f at 340
                    -0.3f at 405
                    0f at 500
                }
            )
        }
    }

    Box(
        Modifier
            .semantics {
                onLongClick(menuLabel) {
                    open(Offset.Zero)
                    true
                }
            }
            .pointerInput(identity) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var lastPosition = down.position
                    val releasedOrCanceled = withTimeoutOrNull(1_000L) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (
                                change == null ||
                                !change.pressed ||
                                event.changes.count { it.pressed } != 1 ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                change.isConsumed
                            ) {
                                return@withTimeoutOrNull true
                            }
                            lastPosition = change.position
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (releasedOrCanceled == null) {
                        open(lastPosition)
                        // Consume the rest of this gesture so release cannot activate a link or button.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
    ) {
        Box(Modifier.graphicsLayer { translationX = shake.value * amplitude }) { content() }
        touch?.let { pressedAt ->
            val gap = with(density) { 48.dp.roundToPx() }
            val margin = with(density) { 12.dp.roundToPx() }
            val positioner = remember(pressedAt, gap, margin) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                        bubbleMenuOffset(anchorBounds, IntOffset(pressedAt.x.roundToInt(), pressedAt.y.roundToInt()), windowSize, popupContentSize, gap, margin)
                }
            }
            val scale = remember { Animatable(0.12f) }
            LaunchedEffect(Unit) { scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
            Popup(popupPositionProvider = positioner, onDismissRequest = { touch = null }, properties = PopupProperties(focusable = true)) {
                Surface(
                    modifier = Modifier.width(224.dp).glassBackdrop(RoundedCornerShape(22.dp), captureAsPopup = true).graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = scale.value
                        transformOrigin = TransformOrigin.Center
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = if (dev.chungjungsoo.gptmobile.presentation.common.hasGlassBackdrop()) androidx.compose.ui.graphics.Color.Transparent else dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens().background,
                    contentColor = dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens().foreground,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 16.dp
                ) {
                    // Leave room above/below a central thumb when neither horizontal side fits.
                    Column(Modifier.heightIn(max = minOf(420f, configuration.screenHeightDp * 0.4f).dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        Text(menuLabel, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        actions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                leadingIcon = { Icon(action.icon, contentDescription = null) },
                                enabled = action.enabled,
                                colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.primary),
                                onClick = {
                                    touch = null
                                    action.invoke()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
