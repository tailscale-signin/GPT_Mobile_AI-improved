package com.example.gptmobileai.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.debug.ToolMetricsCollector

@Composable
fun ToolAnalyticsPanel(
    toolMetrics: Map<String, ToolMetricsCollector.LiveToolMetrics>,
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
                text = "🛠️ TOOL ANALYTICS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (toolMetrics.isEmpty()) {
                Text(
                    text = "No tool calls yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                toolMetrics.forEach { (toolId, metrics) ->
                    ToolMetricRow(toolId, metrics)
                }
            }
        }
    }
}

@Composable
private fun ToolMetricRow(toolId: String, metrics: ToolMetricsCollector.LiveToolMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${metrics.totalCalls} calls | ${metrics.totalTokensUsed} tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = String.format("%.0f%%", metrics.successRate),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (metrics.successRate >= 95.0) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
            Text(
                text = "${metrics.avgExecutionTimeMs.toInt()}ms avg",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
