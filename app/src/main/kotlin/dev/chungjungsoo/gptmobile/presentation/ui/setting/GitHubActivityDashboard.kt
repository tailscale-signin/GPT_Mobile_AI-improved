package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.jsonObject

@Composable
internal fun GitHubActivityDashboard(state: GitHubWorkspaceState, model: GitHubWorkspaceViewModel, onOpenPulls: () -> Unit, onOpenUrl: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Repository overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(state.activityUpdatedAt?.let { "Updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))}" } ?: "Loading activity…", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = model::refreshActivity, enabled = !state.busy) { Text("Refresh") }
    }
    state.commitDetail?.let { detail ->
        SettingsPanel("Commit changes") {
            TextButton(onClick = model::closeCommit) { Text("Back to overview") }
            androidx.compose.foundation.text.selection.SelectionContainer { Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
        return
    }
    SettingsPanel("Pull requests") {
        val failed = state.pullRequests.count { it.text("check_state") == "FAILED" }
        val running = state.pullRequests.count { it.text("check_state") == "RUNNING" }
        val passed = state.pullRequests.count { it.text("check_state") == "PASSED" }
        Text("$failed failed · $running running · $passed passed", style = MaterialTheme.typography.bodyMedium)
        if (state.pullRequests.isEmpty() && !state.busy) Text("No open pull requests.")
        state.pullRequests.sortedBy {
            when (it.text("check_state")) {
                "FAILED" -> 0
                "RUNNING" -> 1
                else -> 2
            }
        }.take(5).forEach { pr ->
            Card(onClick = {
                model.inspectPullRequest(pr.text("number").toInt())
                onOpenPulls()
            }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GitHubCheckBadge(pr.text("check_state"))
                        Text("#${pr.text("number")} · ${if (pr.flag("draft")) "Draft" else "Open"}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(pr.text("title"), style = MaterialTheme.typography.titleSmall)
                    Text("${pr["head"]?.jsonObject?.text("ref")} → ${pr["base"]?.jsonObject?.text("ref")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.morePullRequests || state.pullRequests.size > 5) TextButton(onClick = onOpenPulls) { Text("View all pull requests") }
    }
    SettingsPanel("Latest commits · ${state.selection?.ref.orEmpty()}") {
        if (state.commits.isEmpty() && !state.busy) Text("No commits available on this branch.")
        state.commits.forEach { commit ->
            val info = commit["commit"]?.jsonObject
            Card(onClick = { model.inspectCommit(commit.text("sha")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(info?.text("message").orEmpty().lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.titleSmall)
                    Text("${commit.text("sha").take(8)} · ${info?.get("author")?.jsonObject?.text("name").orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Text(info?.get("author")?.jsonObject?.text("date").orEmpty(), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { commit.text("html_url").takeIf { it.startsWith("https://github.com/") }?.let(onOpenUrl) }) { Text("Open on GitHub") }
                }
            }
        }
        if (state.moreCommits) TextButton(onClick = model::moreCommits, enabled = !state.busy) { Text("Load older commits") }
    }
}

@Composable
internal fun GitHubCheckBadge(state: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (label, colour) = when (state) {
        "PASSED" -> "Passed" to androidx.compose.ui.graphics.Color(if (dark) 0xFF8EE1AC else 0xFF217747)
        "FAILED" -> "Failed" to MaterialTheme.colorScheme.error
        "RUNNING" -> "Running" to androidx.compose.ui.graphics.Color(if (dark) 0xFFFFD980 else 0xFF8A6000)
        "NONE" -> "No checks" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Unavailable" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = colour.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = colour, style = MaterialTheme.typography.labelMedium)
    }
}
