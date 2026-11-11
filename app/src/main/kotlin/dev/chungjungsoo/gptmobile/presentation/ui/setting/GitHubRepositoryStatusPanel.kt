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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubDraftRepair
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
internal fun rememberGitHubStatusActions(model: GitHubLiveStatusViewModel, connection: ToolConnection, selection: GitHubRepositoryContext, chatId: Int?, profile: PlatformV2?, onRepairDraft: ((GitHubDraftRepair) -> Unit)?): GitHubStatusActions {
    val watched by remember(model, chatId) { model.observeWatches(chatId) }.collectAsStateWithLifecycle(emptyList())
    val track: ((GitHubStatusActionTarget, Boolean) -> Unit)? = if (profile != null && chatId != null) {
        { target, enabled -> model.setTracking(connection, selection, profile.uid, chatId, target, enabled) }
    } else {
        null
    }
    return GitHubStatusActions(
        watched.filter { it.connectionUid == connection.connectionUid && it.profileUid == profile?.uid && "${it.owner}/${it.repo}".equals(selection.fullName, true) },
        track,
        onRepairDraft?.let { queue -> { target -> model.repairDraft(connection, selection, target, queue) } }
    )
}

@Composable
internal fun GitHubRepositoryStatusPanel(connection: ToolConnection, selection: GitHubRepositoryContext, pulls: List<JsonObject>, refreshPulls: () -> Unit, chatId: Int? = null, profile: PlatformV2? = null, graphContext: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext? = null, model: GitHubLiveStatusViewModel = hiltViewModel(), graphProfile: PlatformV2? = null, onConfigureGateway: (() -> Unit)? = null, actions: GitHubStatusActions? = null) {
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
    var query by remember(selection) { androidx.compose.runtime.mutableStateOf("") }
    var details by remember(selection) { mutableStateOf(false) }
    var searching by remember(selection) { mutableStateOf(false) }
    var confirmation by remember(selection) { androidx.compose.runtime.mutableStateOf<Pair<GitHubLiveProcess, String>?>(null) }
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
    val summary = gitHubStatusSummary(state, now)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitHubRepositoryStatusSummary(selection, summary, pulls.size, profile?.name, model::refresh) { open("https://github.com/${selection.fullName}/actions") }
        if (graphContext != null) {
            dev.chungjungsoo.gptmobile.presentation.ui.chat.RepositoryGraphStatusMap(graphContext, graphProfile, onConfigureGateway = onConfigureGateway)
        } else {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Repository map", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Choose a repository, revision and AI profile to open its source map. Select the map’s gateway connection in GitHub settings.", style = MaterialTheme.typography.bodySmall)
                    onConfigureGateway?.let { configure ->
                        TextButton(onClick = configure) { Text("GitHub settings") }
                    }
                }
            }
        }
        profile?.let { GitHubGatewayStatusPanel(it, now) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Checks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (summary.other > 0) Text("${summary.other} unverified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = {
                searching = !searching
                if (!searching) query = ""
            }) { Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close check search" else "Find a check", Modifier.size(20.dp)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("CURRENT" to "Current", "ACTIVE" to "Running", "HISTORY" to "Recent").forEach { (value, label) ->
                androidx.compose.material3.FilterChip(selected = state.filter == value, onClick = {
                    selectedRun = null
                    model.filter(value)
                }, label = { Text(label) })
            }
        }
        if (searching) androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Find a check") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        GitHubStatusOverview(pulls, state.rows.filter { query.isBlank() || it.title.contains(query, true) || it.status.contains(query, true) }, selection.ref, summary.stale, ::open, actions) { row ->
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
                            Text(
                                dev.chungjungsoo.gptmobile.data.github.githubWorkflowDuration(row.started, row.completed, row.status, now),
                                style = MaterialTheme.typography.labelSmall
                            )
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
        if (state.hasMore) androidx.compose.material3.TextButton(onClick = model::more) { Text("More checks") }
        state.checkedAt?.let { Text("Loaded results · updated ${githubElapsedTime(it, now)} ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val remoteActivity = state.rows.filter { it.parentId == null }.map { row -> GitHubActivityItem("run:${row.id}:${row.attempt}", row.started ?: 0, "${row.title} · ${row.status} · attempt ${row.attempt}", "Revision ${row.headSha}", row.url) } + pulls.map { pr ->
            GitHubActivityItem("pr:${pr.text("number")}", runCatching { java.time.Instant.parse(pr.text("updated_at").ifBlank { pr.text("created_at") }).toEpochMilli() }.getOrDefault(0), "PR #${pr.text("number")}: ${pr.text("title")} · ${pr.text("state")}", "Current loaded PR observation", pr.text("html_url"))
        }
        GitHubActivityPanel(connection, selection, chatId, remoteActivity)
        GitHubStatusDisclosure("Connection details", details, { details = !details }, if (state.error != null) "Needs attention" else null)
        if (details) {
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text("${state.rows.count { it.parentId == null }} loaded runs · ${state.total?.toString() ?: "total not measured"}. Missing or skipped checks are not a pass.", style = MaterialTheme.typography.bodySmall)
            if (state.nextCheckAt > 0) Text("Next refresh ${java.text.DateFormat.getTimeInstance().format(java.util.Date(state.nextCheckAt))}", style = MaterialTheme.typography.bodySmall)
            state.health.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.metrics?.let { metrics ->
                listOf("physical_requests", "cache_hits", "queued_requests", "singleflight_joins", "downloaded_bytes", "queue_wait_total_ms", "queue_wait_max_ms").forEach { key -> Text("${key.replace('_', ' ')}: ${metrics[key] ?: "Not measured"}", style = MaterialTheme.typography.bodySmall) }
            }
            Text("First response, token use, verification time and task completion rate: see conversation diagnostics. No estimates are substituted.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun GitHubRepositoryStatusSummary(selection: GitHubRepositoryContext, summary: GitHubStatusSummary, pullCount: Int, profileName: String?, onRefresh: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("github_status_summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .32f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("AT A GLANCE", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    GitHubStatusBadge(summary.badge, if (summary.stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh repository status", Modifier.size(20.dp)) }
                }
                Text(summary.headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                Text(selection.fullName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AccountTree, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selection.ref, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onOpen) { Icon(Icons.Rounded.OpenInNew, "Open repository on GitHub", Modifier.size(18.dp)) }
                }
                profileName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        GitHubStatusMetricPair(
            { GitHubStatusMetric("Passing", if (summary.measured) summary.passed.toString() else "Not measured", githubCheckColor("Passed"), it) },
            { GitHubStatusMetric("Running", if (summary.measured) summary.running.toString() else "Not measured", MaterialTheme.colorScheme.primary, it) }
        )
        GitHubStatusMetricPair(
            { GitHubStatusMetric("Needs attention", if (summary.measured) summary.failed.toString() else "Not measured", MaterialTheme.colorScheme.error, it) },
            { GitHubStatusMetric("Pull requests", pullCount.toString(), MaterialTheme.colorScheme.secondary, it) }
        )
    }
}
