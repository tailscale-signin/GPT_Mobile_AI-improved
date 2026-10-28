package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/** A shorter model tab cannot collapse the turn and clamp the conversation's scroll offset. */
@Composable
internal fun StableChatResponseViewport(contentKey: Any, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Pixel heights belong to this width, density and font scale. Reusing heights
        // after rotation, split-screen resizing or a font change distorts the turn.
        val measuredHeights = remember(maxWidth, density.density, density.fontScale) { mutableStateMapOf<Any, Int>() }
        val minimumHeight = with(density) { (measuredHeights.values.maxOrNull() ?: 0).toDp() }
        Box(Modifier.fillMaxWidth().heightIn(min = minimumHeight)) {
            // Measure natural content rather than the reserved outer height.
            Column(Modifier.fillMaxWidth().onSizeChanged { measuredHeights[contentKey] = it.height }, content = content)
        }
    }
}
