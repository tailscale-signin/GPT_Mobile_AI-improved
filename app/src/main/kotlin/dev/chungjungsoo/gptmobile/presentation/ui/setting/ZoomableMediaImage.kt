package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize

@Composable
internal fun ZoomableMediaImage(bitmap: Bitmap) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    fun clampPan(value: Offset): Offset {
        val x = size.width * (zoom - 1f) / 2f
        val y = size.height * (zoom - 1f) / 2f
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    fun changeZoom(value: Float) {
        zoom = value.coerceIn(1f, 4f)
        pan = clampPan(pan)
    }
    Column {
        Row {
            TextButton(onClick = { changeZoom(zoom - 0.5f) }, enabled = zoom > 1f) { Text("Zoom out") }
            TextButton(onClick = { changeZoom(zoom + 0.5f) }, enabled = zoom < 4f) { Text("Zoom in") }
            TextButton(onClick = { changeZoom(1f) }) { Text("Fit") }
        }
        Box(
            Modifier.fillMaxWidth().clipToBounds().onSizeChanged {
                size = it
                pan = clampPan(pan)
            }.pointerInput(bitmap) {
                detectTransformGestures { _, movement, scale, _ ->
                    changeZoom(zoom * scale)
                    pan = clampPan(pan + movement)
                }
            }
        ) {
            Image(
                bitmap.asImageBitmap(),
                "Media returned by the tool",
                Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }.semantics {
                    stateDescription = "${(zoom * 100).toInt()} percent zoom"
                    customActions = listOf(
                        CustomAccessibilityAction("Zoom in") {
                            changeZoom(zoom + 0.5f)
                            true
                        },
                        CustomAccessibilityAction("Zoom out") {
                            changeZoom(zoom - 0.5f)
                            true
                        },
                        CustomAccessibilityAction("Fit image") {
                            changeZoom(1f)
                            true
                        }
                    )
                }
            )
        }
    }
}
