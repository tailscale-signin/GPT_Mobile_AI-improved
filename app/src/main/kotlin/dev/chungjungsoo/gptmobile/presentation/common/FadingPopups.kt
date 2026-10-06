package dev.chungjungsoo.gptmobile.presentation.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet as MaterialModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private const val BACK_FADE_MILLIS = 500

/** Defer dismissing the owner until the actual popup finishes fading, including system back. */
@Composable
private fun rememberFadeDismiss(onDismiss: () -> Unit): Pair<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, () -> Unit> {
    val opacity = remember { Animatable(1f) }
    val callback by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            scope.launch {
                opacity.animateTo(0f, tween(BACK_FADE_MILLIS))
                callback()
            }
        }
    }
    return opacity to dismiss
}

@Composable
fun FadingAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    properties: DialogProperties = DialogProperties()
) {
    val (opacity, dismiss) = rememberFadeDismiss(onDismissRequest)
    MaterialAlertDialog(
        onDismissRequest = dismiss,
        confirmButton = confirmButton,
        modifier = modifier.graphicsLayer { alpha = opacity.value },
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        properties = properties
    )
}

@Composable
fun FadingDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val (opacity, dismiss) = rememberFadeDismiss(onDismissRequest)
    ComposeDialog(onDismissRequest = dismiss, properties = properties) {
        Box(Modifier.graphicsLayer { alpha = opacity.value }) { content() }
    }
}

@Composable
fun FadingDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (expanded) {
        // Recreate on opening so a previously dismissed menu cannot stay transparent.
        val (opacity, dismiss) = rememberFadeDismiss(onDismissRequest)
        MaterialDropdownMenu(expanded = true, onDismissRequest = dismiss, modifier = modifier.graphicsLayer { alpha = opacity.value }, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FadingModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val (opacity, dismiss) = rememberFadeDismiss(onDismissRequest)
    MaterialModalBottomSheet(onDismissRequest = dismiss, modifier = modifier.graphicsLayer { alpha = opacity.value }, sheetState = sheetState) {
        BackHandler { dismiss() }
        content()
    }
}
