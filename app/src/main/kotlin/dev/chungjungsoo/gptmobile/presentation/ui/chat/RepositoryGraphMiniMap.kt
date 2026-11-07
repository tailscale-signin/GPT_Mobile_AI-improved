package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
        Modifier.fillMaxWidth().height(graphHeight)
            .testTag("github_repository_bubble_network")
            .semantics { contentDescription = "Interactive repository network with ${nodes.size} source-backed bubbles" }
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
        Canvas(Modifier.fillMaxSize()) {
            edges.forEach { edge ->
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
                val color = if (focused) colors.primary else colors.outline.copy(alpha = .6f)
                drawLine(color, from, to, strokeWidth = if (focused) 2.dp.toPx() else 1.5.dp.toPx())
                val arrow = unit * 7.dp.toPx()
                val wing = Offset(-unit.y, unit.x) * 3.5.dp.toPx()
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
            val accent = when (kind.lowercase()) {
                "community", "module", "package" -> colors.tertiary
                "file" -> colors.secondary
                else -> colors.primary
            }
            Column(
                Modifier.offset { IntOffset((points[index].x - halfWidth).roundToInt(), (points[index].y - halfHeight).roundToInt()) }
                    .width(bubbleWidth).height(bubbleHeight)
                    .testTag("github_repository_node_$id")
                    .semantics {
                        selected = isSelected
                        contentDescription = path
                    }
                    .clickable(role = Role.Button, onClickLabel = "Inspect source") { onSelect(node) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(shape = CircleShape, color = accent.copy(alpha = if (isSelected) .24f else .12f), border = BorderStroke(if (isSelected) 2.dp else 1.dp, accent), modifier = Modifier.size(48.dp)) {
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
