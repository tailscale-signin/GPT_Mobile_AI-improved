package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Measure the detail pane after its parent has allocated rails, hinges and insets. */
@Composable
internal fun ConversationViewport(content: @Composable (width: Dp, height: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        content(maxWidth, maxHeight)
    }
}
