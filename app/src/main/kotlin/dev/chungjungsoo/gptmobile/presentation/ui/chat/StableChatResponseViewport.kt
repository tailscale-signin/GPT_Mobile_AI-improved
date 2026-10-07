package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
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
    val measuredHeights = remember { mutableStateMapOf<Any, Int>() }
    val minimumHeight = with(LocalDensity.current) { (measuredHeights.values.maxOrNull() ?: 0).toDp() }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = minimumHeight)
    ) {
        // Measure the natural content, so collapsing details in the same tab
        // can still remove its space. Only other model tabs reserve their height.
        Column(Modifier.fillMaxWidth().onSizeChanged { measuredHeights[contentKey] = it.height }, content = content)
    }
}
