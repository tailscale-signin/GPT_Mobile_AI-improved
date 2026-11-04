package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.githubElapsedTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

@Composable
internal fun GitHubStatusLight(status: String, modifier: Modifier = Modifier) {
    val color = when (status.uppercase()) {
        "SUCCESS", "PASSED", "MERGED" -> Color(0xFF55C99A)
        "FAILURE", "FAILED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "BLOCKED", "CONFLICTS" -> MaterialTheme.colorScheme.error
        "RUNNING", "IN_PROGRESS", "QUEUED", "WAITING", "PENDING", "REQUESTED", "RETRYING", "DRAFT" -> Color(0xFFE8BC58)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(modifier.size(12.dp)) {
        drawCircle(color.copy(alpha = 0.18f), radius = size.minDimension / 2)
        drawCircle(color, radius = size.minDimension * 0.30f)
    }
}

@Composable
internal fun GitHubRepositoryStatusPanel(connection: ToolConnection, selection: GitHubRepositoryContext, pulls: List<JsonObject>, refreshPulls: () -> Unit, chatId: Int? = null, model: GitHubLiveStatusViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val currentPulls by androidx.compose.runtime.rememberUpdatedState(pulls)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connection, selection, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observe(connection, selection, refreshPulls) { currentPulls.mapNotNull { (it["head"] as? JsonObject)?.text("sha") }.filter { it.matches(Regex("[a-fA-F0-9]{40}")) }.toSet() } }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(15_000)
            }
        }
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var selectedRun by remember(selection) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var query by remember { androidx.compose.runtime.mutableStateOf("") }
    var details by remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmation by remember { androidx.compose.runtime.mutableStateOf<Pair<GitHubLiveProcess, String>?>(null) }
    fun open(url: String) {
        if (url.startsWith("https://github.com/")) uri.openUri(url)
    }
    confirmation?.let { (row, action) ->
        dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(action.replace('_', ' ')) },
            text = { Text("${row.title} · attempt ${row.attempt} · ${row.headSha.take(12)}. GitHub state is rechecked before this action.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmation = null
                    model.mutate(connection, selection, row, action)
                }) { Text("Confirm") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmation = null }) { Text("Back") } }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repository status", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ACTIVE" to "Active", "CURRENT" to "Current revision", "HISTORY" to "History").forEach { (value, label) ->
                androidx.compose.material3.FilterChip(selected = state.filter == value, onClick = { model.filter(value) }, label = { Text(label) })
            }
        }
        Text("Showing ${state.rows.count { it.parentId == null }} of ${state.total?.toString() ?: "unknown total"} runs · ${state.phase.lowercase()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row {
            androidx.compose.material3.TextButton(onClick = model::refresh) { Text("Retry refresh") }
            androidx.compose.material3.TextButton(onClick = { open("https://github.com/${selection.fullName}/actions") }) { Text("Open GitHub") }
        }
        if (state.rows.isEmpty()) {
            Text(
                when (state.phase) {
                    "LOADING" -> "Loading workflow results…"
                    "EMPTY" -> "No runs match this filter."
                    else -> "No results available. Retry refresh when access is restored."
                }
            )
        }
        androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter loaded runs, jobs and steps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        GitHubStatusOverview(pulls, state.rows.filter { query.isBlank() || it.title.contains(query, true) || it.status.contains(query, true) }, selection.ref, state.phase in setOf("STALE", "OFFLINE", "AUTHORIZATION"), ::open) { row ->
            selectedRun = row.id
            if (row.id !in state.expanded) model.expand(row.id)
        }
        if (selectedRun != null) androidx.compose.material3.TextButton(onClick = { selectedRun = null }) { Text("Close check details") }
        state.rows.filter { selectedRun != null && (it.id == selectedRun || it.parentId == selectedRun) }.filter { query.isBlank() || (it.title + it.status + it.stepDetails.joinToString()).contains(query, true) }.forEach { row ->
            androidx.compose.runtime.key(row.id) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f)), modifier = Modifier.fillMaxWidth().padding(start = if (row.parentId == null) 0.dp else 12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            GitHubStatusLight(row.status)
                            Column(Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall)
                                Text("${row.status.replace('_', ' ')} · attempt ${row.attempt}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (row.steps.isNotBlank()) Text(row.steps, style = MaterialTheme.typography.bodySmall)
                            }
                            row.started?.let { start ->
                                Text(
                                    if (row.completed != null) {
                                        "Total ${githubElapsedTime(start, row.completed)}"
                                    } else if (row.status in setOf("in_progress", "queued", "waiting", "pending", "requested")) {
                                        githubElapsedTime(start, now)
                                    } else {
                                        "Total unavailable"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (row.parentId == null) {
                            FlowRow {
                                androidx.compose.material3.TextButton(onClick = { model.expand(row.id) }) { Text(if (row.id in state.expanded) "Collapse jobs" else "Expand jobs") }
                                androidx.compose.material3.TextButton(onClick = { open(row.url) }) { Text("Open logs") }
                                androidx.compose.material3.TextButton(onClick = { open(row.url + "#artifacts") }) { Text("Artifacts") }
                            }
                            if (row.comparison.isNotBlank()) Text(row.comparison, style = MaterialTheme.typography.bodySmall)
                            if (row.id in state.expanded) {
                                FlowRow {
                                    androidx.compose.material3.TextButton(onClick = { model.attempt(row.id, row.attempt - 1) }, enabled = row.attempt > 1) { Text("Previous attempt") }
                                    androidx.compose.material3.TextButton(onClick = { model.attempt(row.id, row.attempt + 1) }) { Text("Next attempt") }
                                    androidx.compose.material3.TextButton(onClick = { model.compare(row.id) }, enabled = row.attempt > 1) { Text("Compare attempts") }
                                }
                                FlowRow {
                                    if (row.status in setOf("in_progress", "queued", "waiting", "pending", "requested")) {
                                        androidx.compose.material3.TextButton(onClick = { confirmation = row to "cancel_workflow" }) { Text("Cancel run") }
                                    } else {
                                        androidx.compose.material3.TextButton(onClick = { confirmation = row to "rerun_workflow" }) { Text("Rerun all") }
                                        androidx.compose.material3.TextButton(onClick = { confirmation = row to "rerun_failed_jobs" }) { Text("Rerun failed") }
                                    }
                                }
                                if (row.id in state.moreJobs) androidx.compose.material3.TextButton(onClick = { model.moreJobs(row.id) }) { Text("Load more jobs · results incomplete") }
                            }
                        } else {
                            row.stepDetails.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            androidx.compose.material3.TextButton(onClick = { open(row.url) }) { Text("Open job logs") }
                        }
                    }
                }
            }
        }
        if (state.hasMore) androidx.compose.material3.TextButton(onClick = model::more) { Text("Load more runs · results incomplete") }
        state.checkedAt?.let { Text("Updated ${githubElapsedTime(it, now)} ago · ${pulls.size} PRs loaded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.nextCheckAt > 0) Text("Next check after ${java.text.DateFormat.getTimeInstance().format(java.util.Date(state.nextCheckAt))}", style = MaterialTheme.typography.bodySmall)
        val remoteActivity = state.rows.filter { it.parentId == null }.map { row -> GitHubActivityItem("run:${row.id}:${row.attempt}", row.started ?: 0, "${row.title} · ${row.status} · attempt ${row.attempt}", "Revision ${row.headSha}", row.url) } + pulls.map { pr ->
            GitHubActivityItem("pr:${pr.text("number")}", runCatching { java.time.Instant.parse(pr.text("updated_at").ifBlank { pr.text("created_at") }).toEpochMilli() }.getOrDefault(0), "PR #${pr.text("number")}: ${pr.text("title")} · ${pr.text("state")}", "Current loaded PR observation", pr.text("html_url"))
        }
        GitHubActivityPanel(connection, selection, chatId, remoteActivity)
        androidx.compose.material3.TextButton(onClick = { details = !details }) { Text(if (details) "Hide integration health" else "Integration health and measurements") }
        if (details) {
            state.health.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.metrics?.let { metrics ->
                listOf("physical_requests", "cache_hits", "queued_requests", "singleflight_joins", "downloaded_bytes", "queue_wait_total_ms", "queue_wait_max_ms").forEach { key -> Text("${key.replace('_', ' ')}: ${metrics[key] ?: "Not measured"}", style = MaterialTheme.typography.bodySmall) }
            }
            Text("First response, token use, verification time and task completion rate: see conversation diagnostics. No estimates are substituted.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
