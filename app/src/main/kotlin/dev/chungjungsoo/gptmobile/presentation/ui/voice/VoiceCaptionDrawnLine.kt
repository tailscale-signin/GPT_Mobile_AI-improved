package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints

/** Cached glyph geometry never changes when the spoken word becomes bold or changes colour. */
@Composable
internal fun VoiceCaptionDrawnLine(text: String, sourceStart: Int, style: TextStyle, width: Int, measurer: TextMeasurer, ink: Color, accent: Color, highlights: VoiceCaptionHighlights, nowNanos: () -> Long, modifier: Modifier) {
    Text(
        text,
        style = style,
        color = Color.Transparent,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithCache {
            val constraints = Constraints(minWidth = width, maxWidth = width)
            val normal = measurer.measure(AnnotatedString(text), style = style, softWrap = false, maxLines = 1, constraints = constraints)
            val bold = measurer.measure(AnnotatedString(text), style = style.copy(fontWeight = FontWeight.Bold), softWrap = false, maxLines = 1, constraints = constraints)
            val normalPaths = mutableMapOf<IntRange, Path>()
            val boldPaths = mutableMapOf<IntRange, Path>()
            fun local(range: IntRange): IntRange? {
                val from = (range.first - sourceStart).coerceAtLeast(0)
                val to = (range.last + 1 - sourceStart).coerceAtMost(text.length)
                return (from until to).takeUnless(IntRange::isEmpty)
            }
            fun path(range: IntRange, boldWeight: Boolean = false): Path = if (boldWeight) {
                boldPaths.getOrPut(range) { bold.getPathForRange(range.first, range.last + 1) }
            } else {
                normalPaths.getOrPut(range) { normal.getPathForRange(range.first, range.last + 1) }
            }
            onDrawBehind {
                val words = highlights.visible(nowNanos()).mapNotNull { word -> local(word.range)?.let { word to it } }
                val active = words.firstOrNull { it.first.active }?.second
                if (active == null) {
                    drawText(normal, color = ink)
                } else {
                    clipPath(path(active), ClipOp.Difference) { drawText(normal, color = ink) }
                }
                for ((word, range) in words) {
                    val colour = lerp(ink, accent, word.colour)
                    if (!word.active) {
                        clipPath(path(range)) { drawText(normal, color = colour) }
                    } else {
                        val destination = path(range).getBounds()
                        val selected = path(range, boldWeight = true)
                        val origin = selected.getBounds()
                        if (destination.width > 0f && origin.width > 0f) {
                            // Preserve contextual shaping (including RTL) while fitting the bold
                            // glyphs to the original word advances. Neighbouring words never move.
                            withTransform({
                                translate(left = destination.left, top = destination.top - origin.top)
                                scale(scaleX = destination.width / origin.width, scaleY = 1f, pivot = Offset.Zero)
                                translate(left = -origin.left)
                            }) {
                                clipPath(selected) { drawText(bold, color = colour) }
                            }
                        }
                    }
                }
            }
        }
    )
}
