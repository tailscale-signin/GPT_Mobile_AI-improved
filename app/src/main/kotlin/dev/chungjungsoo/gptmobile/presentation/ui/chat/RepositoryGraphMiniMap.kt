package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject

/** Only source relationships are drawn. Each bubble is a real, accessible touch target. */
@Composable
internal fun GraphMiniMap(nodes: List<JsonObject>, edges: List<JsonObject>, selectedId: String? = null, onSelect: (JsonObject) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val bubbleHeight = (56.dp + 32.dp * density.fontScale).coerceAtLeast(88.dp)
    val graphHeight = if (nodes.size > 6) (bubbleHeight + 8.dp) * ((nodes.size + 2) / 3) else (bubbleHeight * 3.5f).coerceAtLeast(310.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(graphHeight).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = .035f), colors.surfaceContainer.copy(alpha = .65f))))
            .testTag("github_repository_bubble_network")
            .semantics { contentDescription = "Interactive repository network with ${nodes.size} source-backed bubbles" },
        // Canvas coordinates are physical; bubble placement must use the same
        // origin in every locale. Text retains the surrounding layout direction.
        contentAlignment = AbsoluteAlignment.TopLeft
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { graphHeight.toPx() }
        val bubbleWidth = (maxWidth / 3).coerceAtMost(100.dp)
        val halfWidth = with(density) { bubbleWidth.toPx() / 2 }
        val halfHeight = with(density) { bubbleHeight.toPx() / 2 }
        val points = remember(nodes.size, widthPx, heightPx, halfWidth, halfHeight) {
            nodes.indices.map { index ->
                if (nodes.size == 1) {
                    Offset(widthPx / 2, heightPx / 2)
                } else if (nodes.size <= 6) {
                    val angle = 2 * Math.PI * index / nodes.size - Math.PI / 2
                    Offset(widthPx / 2 + cos(angle).toFloat() * (widthPx / 2 - halfWidth), heightPx / 2 + sin(angle).toFloat() * (heightPx / 2 - halfHeight))
                } else {
                    val rows = (nodes.size + 2) / 3
                    val row = index / 3
                    val column = if (row % 2 == 0) index % 3 else 2 - index % 3
                    Offset(widthPx * (column + .5f) / 3, heightPx * (row + .5f) / rows)
                }
            }
        }
        val nodeIndices = remember(nodes) { nodes.mapIndexed { index, node -> node.graphText("id") to index }.toMap() }
        val visibleEdges = remember(edges, nodeIndices) { edges.filter { it.graphText("source") in nodeIndices && it.graphText("target") in nodeIndices } }
        Canvas(Modifier.fillMaxSize()) {
            val grid = 24.dp.toPx()
            var x = grid / 2
            while (x < size.width) {
                var y = grid / 2
                while (y < size.height) {
                    drawCircle(colors.outlineVariant.copy(alpha = .45f), radius = .7.dp.toPx(), center = Offset(x, y))
                    y += grid
                }
                x += grid
            }
            visibleEdges.forEach { edge ->
                val source = nodeIndices[edge.graphText("source")] ?: return@forEach
                val target = nodeIndices[edge.graphText("target")] ?: return@forEach
                if (source == target) return@forEach
                // The glyph sits above the label inside the touch target.
                val glyphOffset = Offset(0f, halfHeight - 24.dp.toPx())
                val start = points[source] - glyphOffset
                val end = points[target] - glyphOffset
                val vector = end - start
                val length = vector.getDistance()
                if (length <= 52.dp.toPx()) return@forEach
                val unit = vector / length
                val from = start + unit * 25.dp.toPx()
                val to = end - unit * 25.dp.toPx()
                val focused = selectedId != null && (nodes[source].graphText("id") == selectedId || nodes[target].graphText("id") == selectedId)
                val color = if (focused) colors.primary else colors.outline.copy(alpha = if (selectedId == null) .6f else .25f)
                val control = (from + to) / 2f + Offset(-unit.y, unit.x) * (length * .08f).coerceAtMost(18.dp.toPx())
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    quadraticBezierTo(control.x, control.y, to.x, to.y)
                }
                drawPath(path, color, style = Stroke(width = if (focused) 2.5.dp.toPx() else 1.5.dp.toPx()))
                val tangent = (to - control) / (to - control).getDistance().coerceAtLeast(1f)
                val arrow = tangent * 7.dp.toPx()
                val wing = Offset(-tangent.y, tangent.x) * 3.5.dp.toPx()
                drawLine(color, to, to - arrow + wing, strokeWidth = 1.5.dp.toPx())
                drawLine(color, to, to - arrow - wing, strokeWidth = 1.5.dp.toPx())
            }
        }
        nodes.forEachIndexed { index, node ->
            val id = node.graphText("id")
            val name = node.graphText("name", node.graphText("qualified", id)).substringAfterLast('/')
            val path = node.graphText("path", node.graphText("qualified", name))
            val kind = node.graphText("kind")
            val isSelected = id == selectedId
            val connections = visibleEdges.count { it.graphText("source") == id || it.graphText("target") == id }
            val outside = edges.count { it.graphText("source") == id || it.graphText("target") == id } - connections
            val accent = when (kind.lowercase()) {
                "community", "module", "package" -> colors.tertiary
                "file" -> colors.secondary
                else -> colors.primary
            }
            Column(
                Modifier.absoluteOffset { IntOffset((points[index].x - halfWidth).roundToInt(), (points[index].y - halfHeight).roundToInt()) }
                    .width(bubbleWidth).height(bubbleHeight)
                    .testTag("github_repository_node_$id")
                    .semantics {
                        selected = isSelected
                        contentDescription = path
                        stateDescription = "$connections visible source connections; $outside connections outside this diagram"
                    }
                    .clickable(role = Role.Button, onClickLabel = "Inspect source") { onSelect(node) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(shape = CircleShape, color = if (isSelected) accent.copy(alpha = .24f) else colors.surfaceContainerHigh, border = BorderStroke(if (isSelected) 2.dp else 1.dp, accent.copy(alpha = if (isSelected) 1f else .65f)), shadowElevation = if (isSelected) 3.dp else 1.dp, modifier = Modifier.size(48.dp)) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when (kind.lowercase()) {
                                "file" -> Icons.Outlined.Description
                                "community", "module", "package" -> Icons.Outlined.AccountTree
                                else -> Icons.Outlined.Code
                            },
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
                Text(name, style = MaterialTheme.typography.labelSmall, color = colors.onSurface, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
