package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val DebugMemoryPink = Color(0xFFFF5CAA)

@Composable
internal fun ReviewerDebugText(content: String, modifier: Modifier = Modifier) {
    DebugActivityCard("", content, reviewer = true, modifier = modifier)
}
