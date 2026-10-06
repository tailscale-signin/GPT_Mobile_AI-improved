package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun DebugActivityCard(profile: String, content: String, reviewer: Boolean, modifier: Modifier = Modifier) {
    val activity = remember(content, reviewer) { debugActivityContent(content, reviewer) }
    val accent = if (reviewer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = accent.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (reviewer) Icons.Rounded.FactCheck else Icons.Rounded.Hub, null, tint = accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(activity.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = accent)
                    profile.removeSuffix(" · Reviewer").takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                activity.score?.let { Text("$it/100", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent) }
            }
            activity.verdict?.let {
                Text(
                    when (it) {
                        "PASS" -> "Accepted"
                        "CORRECTED" -> "Corrected"
                        else -> "Needs correction"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            }
            if (activity.body.isNotBlank()) ChatMarkdown(activity.body, modifier = Modifier.fillMaxWidth(), streaming = false)
        }
    }
}
