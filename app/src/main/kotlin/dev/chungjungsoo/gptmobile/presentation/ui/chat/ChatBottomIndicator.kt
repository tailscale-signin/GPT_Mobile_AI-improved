package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdrop
import dev.chungjungsoo.gptmobile.presentation.common.hasGlassBackdrop
import kotlin.math.abs
import kotlinx.coroutines.flow.first

internal const val CHAT_LATEST_OPACITY = 0.50f

/** Follow intent has the same presentation after a button press or a deliberate bottom arrival. */
@Composable
internal fun ChatBottomIndicator(
    listState: LazyListState,
    following: Boolean,
    generating: Boolean,
    animate: Boolean,
    onFollow: () -> Unit
) {
    if (!following) {
        ScrollToBottomButton(isGenerating = generating, onClick = onFollow)
        return
    }
    var reachedBottom by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isAtGitHubBottom() }.first { it }
        reachedBottom = true
    }
    if (!reachedBottom || !generating) return
    // The shared phase respects app/system motion preferences, lifecycle and frame pressure.
    val phase = rememberGitHubProgressMotion(animate)
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier.size(width = 48.dp, height = 48.dp)
            .semantics { contentDescription = "Generating response" }
            .testTag("chat_bottom_generation"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            Spacer(
                Modifier.size(5.dp)
                    .graphicsLayer {
                        val staggered = (phase.value + index / 3f) % 1f
                        alpha = if (animate) 0.35f + 0.65f * (1f - abs(staggered * 2f - 1f)) else 1f
                    }
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
fun ScrollToBottomButton(isGenerating: Boolean = false, onClick: () -> Unit) {
    val tokens = appOverlayTokens(opacity = CHAT_LATEST_OPACITY)
    // Keep the glass at exactly 50%; animate neither the backdrop nor the icon's alpha.
    // Generation remains distinguishable through the themed outline.
    Box(Modifier.size(48.dp).testTag("chat_jump_to_bottom").clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(26.dp).glassBackdrop(CircleShape, opacity = CHAT_LATEST_OPACITY, force = true).testTag("chat_latest_glass"),
            shape = CircleShape,
            color = if (hasGlassBackdrop(force = true)) Color.Transparent else tokens.background,
            contentColor = tokens.foreground,
            border = BorderStroke(
                if (isGenerating) 2.dp else 1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (isGenerating) 0.8f else 0.3f)
            ),
            // A shadow inside this translucent plate would darken its 50% tint.
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom_icon), modifier = Modifier.size(18.dp))
            }
        }
    }
}
