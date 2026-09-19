package com.example.gptmobileai.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.data.model.TokenMetrics

@Composable
fun TokenTimelinePanel(
    tokenEvents: List<TokenMetrics>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "⏱️ TOKEN TIMELINE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (tokenEvents.isEmpty()) {
                Text(
                    text = "No token generation data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    val maxItl = (tokenEvents.maxOfOrNull { it.itlMs } ?: 100.0).coerceAtLeast(1.0).toFloat()
                    val stepX = if (tokenEvents.size > 1) size.width / (tokenEvents.size - 1) else size.width

                    var prevPoint: Offset? = null
                    tokenEvents.forEachIndexed { index, metric ->
                        val x = index * stepX
                        val y = size.height - ((metric.itlMs.toFloat() / maxItl) * size.height)
                        val point = Offset(x, y)
                        prevPoint?.let { prev ->
                            drawLine(
                                color = Color(0xFF4CAF50),
                                start = prev,
                                end = point,
                                strokeWidth = 3f
                            )
                        }
                        drawCircle(
                            color = Color(0xFF2E7D32),
                            radius = 4f,
                            center = point
                        )
                        prevPoint = point
                    }
                }
            }
        }
    }
}
