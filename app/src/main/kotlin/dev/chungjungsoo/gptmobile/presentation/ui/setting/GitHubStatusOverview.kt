package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon
import kotlinx.serialization.json.JsonObject

internal fun githubCheckState(status: String): String = when (status.lowercase()) {
    "success", "passed" -> "Passed"
    "failure", "failed", "timed_out", "action_required", "startup_failure" -> "Failed"
    "in_progress", "queued", "waiting", "pending", "requested" -> "Processing"
    "skipped" -> "Skipped"
    "cancelled", "canceled" -> "Cancelled"
    else -> "Unknown"
}

@Composable
internal fun GitHubStatusOverview(pulls: List<JsonObject>, rows: List<GitHubLiveProcess>, branch: String, stale: Boolean, open: (String) -> Unit, inspect: (GitHubLiveProcess) -> Unit) {
    val roots = rows.filter { it.parentId == null }
    val groups = roots.groupingBy { githubCheckState(it.status) }.eachCount()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Passed", "Processing", "Failed").forEach { label ->
            Surface(shape = RoundedCornerShape(18.dp), color = checkColor(label).copy(alpha = .12f)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text((groups[label] ?: 0).toString(), style = MaterialTheme.typography.headlineSmall, color = checkColor(label))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Text(if (stale) "Saved observations · refresh pending" else "Loaded checks · missing or skipped checks are not a pass", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val pullHeads = pulls.mapNotNull { (it["head"] as? JsonObject)?.text("sha") }.filter { it.isNotBlank() }.toSet()
    pulls.forEach { pull ->
        val head = (pull["head"] as? JsonObject)?.text("sha").orEmpty()
        CheckGroup("#${pull.text("number")} · ${pull.text("title")}", "${pull.text("state")} · ${head.take(12)}", roots.filter { head.isNotBlank() && it.headSha == head }, { open(pull.text("html_url")) }, inspect)
    }
    val other = roots.filter { it.headSha !in pullHeads }
    if (other.isNotEmpty() || pulls.isEmpty()) CheckGroup(branch, "Branch checks", other, null, inspect)
}

@Composable
private fun CheckGroup(title: String, subtitle: String, rows: List<GitHubLiveProcess>, open: (() -> Unit)?, inspect: (GitHubLiveProcess) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (open != null) TextButton(onClick = open) { Text("Open PR") }
            }
            if (rows.isEmpty()) Text("No checks loaded for this PR revision. Open the PR to inspect its checks.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    val label = githubCheckState(row.status)
                    val color = checkColor(label)
                    Surface(onClick = { inspect(row) }, shape = RoundedCornerShape(18.dp), color = color.copy(alpha = .10f), modifier = Modifier.widthIn(min = 140.dp, max = 240.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ThemeIcon(
                                    when (label) {
                                        "Passed" -> Icons.Rounded.CheckCircle
                                        "Failed" -> Icons.Rounded.ErrorOutline
                                        "Processing" -> Icons.Rounded.HourglassTop
                                        else -> Icons.Rounded.HelpOutline
                                    },
                                    null,
                                    Modifier.size(18.dp),
                                    tint = color
                                )
                                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                            }
                            Text(row.title, style = MaterialTheme.typography.bodyMedium)
                            Text("Attempt ${row.attempt} · ${row.headSha.take(8)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun checkColor(label: String): Color = when (label) {
    "Passed" -> Color(0xFF27936C)
    "Failed" -> MaterialTheme.colorScheme.error
    "Processing" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
