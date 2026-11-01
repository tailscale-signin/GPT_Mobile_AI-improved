package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import dev.chungjungsoo.gptmobile.data.github.githubElapsedTime
import kotlinx.coroutines.delay

/** Text highlight and bar share one phase; animation reads are confined to drawing. */
@Composable
internal fun GitHubLoadingHeader(
    activity: String,
    active: Boolean,
    startedAtMillis: Long?,
    completedAtMillis: Long? = null,
    textSizeIncrease: Int = 3,
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val label = remember(activity) { GitHubProgressPresentation.activity(activity) }
    val motion: State<Float> = if (active) {
        rememberInfiniteTransition(label = "github_activity").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "shared_pan")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val shades = listOf(colors.primary.copy(alpha = 0.55f), colors.tertiary, colors.primary)
    val style = MaterialTheme.typography.labelSmall.let { it.copy(fontSize = (it.fontSize.value + textSizeIncrease).sp) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = if (active || progress != null) 8.dp else 0.dp)) {
            Text(
                text = label,
                style = style,
                color = colors.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alignByBaseline().testTag("github_loading_activity")
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            if (active) {
                                val center = (motion.value * 2f - 0.5f) * size.width
                                drawRect(Brush.linearGradient(shades, Offset(center - size.width / 2, 0f), Offset(center + size.width / 2, 0f)), blendMode = BlendMode.SrcIn)
                            }
                        }
                    }.clearAndSetSemantics {
                        contentDescription = label
                        liveRegion = LiveRegionMode.Polite
                    }
            )
            if (startedAtMillis != null) {
                GitHubHeaderElapsedTime(startedAtMillis, completedAtMillis, active, style)
            }
        }
        if (active || progress != null) {
            Canvas(
                Modifier.fillMaxWidth().height(4.dp).testTag("github_loading_bar").clearAndSetSemantics {
                    progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
                }
            ) {
                val corner = CornerRadius(size.height / 2)
                drawRoundRect(colors.primary.copy(alpha = 0.12f), cornerRadius = corner)
                val center = (motion.value * 2f - 0.5f) * size.width
                val gradient = Brush.linearGradient(shades, Offset(center - size.width / 2, 0f), Offset(center + size.width / 2, 0f))
                if (progress != null) {
                    drawRoundRect(gradient, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = corner)
                } else {
                    val start = (center - size.width / 5).coerceIn(0f, size.width)
                    val end = (center + size.width / 5).coerceIn(0f, size.width)
                    drawRoundRect(gradient, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = corner)
                }
            }
        }
    }
}

@Composable
private fun RowScope.GitHubHeaderElapsedTime(start: Long, completed: Long?, active: Boolean, style: androidx.compose.ui.text.TextStyle) {
    var now by remember(start) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, start, completed, active) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = System.currentTimeMillis()
            while (active && completed == null) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }
    }
    Text(
        text = githubElapsedTime(start, completed ?: now),
        color = MaterialTheme.colorScheme.primary,
        style = style.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.alignByBaseline().padding(start = 12.dp).testTag("github_loading_total_time")
    )
}
