package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R

internal fun adjacentResponseIndex(current: Int, count: Int, direction: Int): Int? =
    (current + direction).takeIf { count > 1 && it in 0 until count }

@Composable
internal fun Modifier.responseProfileSwipe(count: Int, selected: Int, select: (Int) -> Unit): Modifier {
    if (count < 2) return this
    val callback = rememberUpdatedState(select)
    val index = rememberUpdatedState(selected)
    val haptic = dev.chungjungsoo.gptmobile.presentation.common.rememberAppHaptic()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val next = stringResource(R.string.audit_next_profile)
    val previous = stringResource(R.string.audit_previous_profile)
    fun move(direction: Int): Boolean = adjacentResponseIndex(index.value, count, direction)?.let {
        callback.value(it)
        haptic(dev.chungjungsoo.gptmobile.presentation.common.AppHaptic.NAVIGATION)
        true
    } ?: false
    return semantics {
        customActions = listOf(CustomAccessibilityAction(previous) { move(-1) }, CustomAccessibilityAction(next) { move(1) })
    }.pointerInput(count, rtl, threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (kotlin.math.abs(distance) >= threshold) move((if (distance < 0) 1 else -1) * if (rtl) -1 else 1)
                distance = 0f
            },
            onHorizontalDrag = { change, amount ->
                distance += amount
                change.consume()
            }
        )
    }
}
