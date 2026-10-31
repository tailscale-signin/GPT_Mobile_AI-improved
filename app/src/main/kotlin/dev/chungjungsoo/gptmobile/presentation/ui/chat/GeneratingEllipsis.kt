package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** A draw-only cursor: it is never appended to the saved or copied answer. */
@Composable
internal fun GeneratingEllipsis(modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "generating_ellipsis").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "dots"
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(28.dp).height(20.dp).testTag("github_generating_ellipsis").clearAndSetSemantics { contentDescription = "Response is still generating" }) {
        repeat(3) { index ->
            val emphasis = ((sin((phase - index / 6f) * 2 * PI) + 1) / 2).toFloat()
            drawCircle(
                color.copy(alpha = 0.3f + emphasis * 0.7f),
                radius = 2.dp.toPx(),
                center = Offset((4 + 9 * index).dp.toPx(), size.height * 0.65f - emphasis * 2.dp.toPx())
            )
        }
    }
}
