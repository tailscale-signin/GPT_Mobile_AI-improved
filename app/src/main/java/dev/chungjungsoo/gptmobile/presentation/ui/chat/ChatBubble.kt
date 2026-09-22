package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.DecelerateInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AgentRun
import dev.chungjungsoo.gptmobile.presentation.service.NotificationManager

/**
 * Telemetry badge showing notice information
 */
@Composable
fun TelemetryBadge(notice: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF374151), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Debug diagnostics card for agent run information
 */
@Composable
fun ChatDebugDiagnosticsCard(
    agentRun: AgentRun?,
    telemetryNotice: String?,
    modifier: Modifier = Modifier
) {
    if (agentRun != null && telemetryNotice != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🔍 Debug Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                Text(
                    text = telemetryNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

/**
 * Helper function to check if message contains telemetry notice
 */
fun isTelemetryNotice(message: String): Boolean {
    return message.contains("telemetry") || message.contains("notice")
}

/**
 * Helper function to extract telemetry notices from list
 */
fun extractTelemetryNotice(notices: List<String>): Pair<String?, List<String>> {
    val filtered = notices.filter { isTelemetryNotice(it) }
    return if (filtered.isNotEmpty()) {
        Pair(filtered.first(), notices - filtered.first())
    } else {
        Pair(null, notices)
    }
}

/**
 * Helper function to build diagnostics HUD text
 */
fun buildDiagnosticsHudText(
    agentRun: AgentRun?,
    telemetryNotice: String?,
    debugMode: Boolean
): String? {
    return if (debugMode && agentRun != null) {
        "Debug mode: ${agentRun.id}"
    } else {
        null
    }
}
