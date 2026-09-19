package com.example.gptmobileai.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.debug.TokenMetricsCollector

@Composable
fun LiveMetricsPanel(
    tokenMetrics: TokenMetricsCollector.LiveTokenMetrics?,
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
                text = "📊 LIVE METRICS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem("TOTAL TOKENS", tokenMetrics?.totalTokens?.toString() ?: "0")
                MetricItem("TOKENS/SEC", String.format("%.1f", tokenMetrics?.throughputTps ?: 0.0))
                MetricItem("TTFT (ms)", tokenMetrics?.ttftMs?.toInt()?.toString() ?: "N/A")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem("ITL (ms)", String.format("%.1f", tokenMetrics?.avgITLms ?: 0.0))
                MetricItem("MODEL", tokenMetrics?.modelId ?: "N/A")
                MetricItem("PROVIDER", tokenMetrics?.provider ?: "N/A")
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(modifier = Modifier.width(100.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
