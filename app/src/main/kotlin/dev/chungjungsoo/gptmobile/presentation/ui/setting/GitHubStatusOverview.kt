package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import kotlinx.serialization.json.JsonObject

@Composable
internal fun GitHubStatusOverview(pulls: List<JsonObject>, rows: List<GitHubLiveProcess>, branch: String, stale: Boolean, open: (String) -> Unit, inspect: (GitHubLiveProcess) -> Unit) {
    val roots = rows.filter { it.parentId == null }
    var allPulls by remember(branch) { mutableStateOf(false) }
    val pullHeads = pulls.mapNotNull { (it["head"] as? JsonObject)?.text("sha") }.filter { it.isNotBlank() }.toSet()
    val visible = if (allPulls) pulls else pulls.take(3)
    visible.forEach { pull ->
        val head = (pull["head"] as? JsonObject)?.text("sha").orEmpty()
        CheckGroup("#${pull.text("number")} · ${pull.text("title")}", if (pull.flag("draft")) "Draft pull request" else "Pull request", roots.filter { head.isNotBlank() && it.headSha == head }, stale, { open(pull.text("html_url")) }, inspect)
    }
    if (pulls.size > 3) TextButton(onClick = { allPulls = !allPulls }) { Text(if (allPulls) "Show fewer requests" else "${pulls.size - 3} more requests") }
    val other = roots.filter { it.headSha !in pullHeads }
    if (other.isNotEmpty() || pulls.isEmpty()) CheckGroup(branch, "Branch checks", other, stale, null, inspect)
}

@Composable
private fun CheckGroup(title: String, subtitle: String, rows: List<GitHubLiveProcess>, stale: Boolean, open: (() -> Unit)?, inspect: (GitHubLiveProcess) -> Unit) {
    var allChecks by remember(title) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (stale) "$subtitle · saved" else subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (open != null) IconButton(onClick = open) { Icon(Icons.Rounded.OpenInNew, "Open pull request", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (rows.isEmpty()) {
                Text("No checks loaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val visible = if (allChecks) rows else rows.take(6)
                visible.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { CheckBubble(it, { inspect(it) }, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (rows.size > 6) TextButton(onClick = { allChecks = !allChecks }) { Text(if (allChecks) "Show fewer checks" else "${rows.size - 6} more checks") }
            }
        }
    }
}

@Composable
private fun CheckBubble(row: GitHubLiveProcess, inspect: () -> Unit, modifier: Modifier) {
    val label = githubCheckState(row.status)
    val color = githubCheckColor(label)
    Surface(onClick = inspect, shape = RoundedCornerShape(18.dp), color = color.copy(alpha = .09f), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    when (label) {
                        "Passed" -> Icons.Rounded.CheckCircle
                        "Failed" -> Icons.Rounded.ErrorOutline
                        "Running" -> Icons.Rounded.HourglassTop
                        else -> Icons.Rounded.HelpOutline
                    },
                    null,
                    Modifier.size(16.dp),
                    tint = color
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            }
            Text(row.title, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
