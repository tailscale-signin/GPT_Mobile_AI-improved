package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.github.GatewaySavedJob
import kotlinx.coroutines.delay

@Composable
fun GatewayJobsPanel(profileUid: String?, connectionUid: String, onOpenConversation: (Int) -> Unit, model: GatewayJobsViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    var monotonic by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var wallTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stopping by remember { mutableStateOf<GatewaySavedJob?>(null) }
    var transcript by remember { mutableStateOf<String?>(null) }
    transcript?.let { GatewayTranscriptDialog(it, onDismiss = { transcript = null }) }
    LaunchedEffect(owner, profileUid) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.visible(profileUid) }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                monotonic = SystemClock.elapsedRealtime()
                wallTime = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    stopping?.let { job ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text("Stop this gateway job?") },
            text = { Text("Request Stop for ${job.job.title}. Leaving this screen or closing the app only stops observation; it does not cancel gateway work.") },
            confirmButton = {
                TextButton(onClick = {
                    model.stop(job)
                    stopping = null
                }) { Text("Request Stop") }
            },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text("Keep running") } }
        )
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item("gateway") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Jobs · ${state.selection.label}", style = MaterialTheme.typography.titleMedium)
                Text("Permitted conversations on this profile's explicit gateway. Open and Reconnect read saved work; they never submit or resume a job.", style = MaterialTheme.typography.bodySmall)
                state.selection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { model.refresh() }, enabled = !state.loading && state.selection.gatewayUid != null) { Text("Refresh jobs") }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        items(state.pending, key = { "command:${it.id}" }) { command ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (command.state == "REJECTED") "Submission rejected" else "Confirming submission", style = MaterialTheme.typography.titleSmall)
                    Text("Saved command ${command.id.take(12)} · conversation ${command.localChatId}", style = MaterialTheme.typography.bodySmall)
                    command.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { model.reconcile(command) }, enabled = !state.loading) { Text("Read command receipt") }
                    TextButton(onClick = { model.retry(command) }, enabled = !state.loading) { Text("Retry saved submission") }
                }
            }
        }
        val groups = state.jobs.groupBy { gatewayJobGroup(it.job.schedulingState, it.job.phase) }
        for (group in listOf("Running", "Queued", "Needs attention", "Recent")) {
            val jobs = groups[group].orEmpty()
            if (jobs.isNotEmpty()) {
                item("group:$group") { Text("$group · ${jobs.size}", style = MaterialTheme.typography.titleMedium) }
                items(jobs, key = { it.row.id }) { saved ->
                    val job = saved.job
                    val open = {
                        if (saved.conversation.profileUid.isBlank()) transcript = saved.conversation.id else model.open(saved, connectionUid, onOpenConversation)
                        Unit
                    }
                    Card(onClick = open, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (job.latestMessageSequence > saved.conversation.seenMessageSequence || (job.conversationRevision ?: 0) > saved.conversation.seenRevision) Text("New updates", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("${if (saved.stale(wallTime)) "Last seen " else ""}${gatewaySchedulingLabel(job.schedulingState)} · ${gatewayPhaseLabel(job.phase)} · ${job.scope}", style = MaterialTheme.typography.bodySmall)
                            Text("${saved.conversation.profileName.ifBlank { "Original profile unavailable" }} · ${job.model.ifBlank { "Model not recorded" }}", style = MaterialTheme.typography.bodySmall)
                            job.repository?.let { repository ->
                                Text("$repository · ${saved.conversation.branch ?: "Source ref unavailable"}", style = MaterialTheme.typography.bodySmall)
                                job.workingBranch?.takeIf { it != saved.conversation.branch }?.let { Text("Working branch: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            Text("Total since accepted: ${gatewayElapsedLabel(saved.elapsedMillis(monotonic))}", style = MaterialTheme.typography.bodySmall)
                            if (job.schedulingState == "queued") Text(job.queuePosition?.let { "Queue position: $it" } ?: "Queue position not reported", style = MaterialTheme.typography.bodySmall)
                            if (saved.stale(wallTime)) Text("Cached observation · ${saved.connection.connectionState.lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            saved.connection.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (!saved.conversation.historyComplete) Text(saved.conversation.historyLimitation ?: "Earlier history has not been verified.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = open, enabled = !state.loading) { Text("Open") }
                                TextButton(onClick = { model.reconnect(saved) }, enabled = !state.loading) { Text("Reconnect") }
                                if (!job.terminal && "cancel" in job.permittedActions) TextButton(onClick = { stopping = saved }, enabled = !state.loading) { Text("Stop") }
                            }
                            TextButton(onClick = { transcript = saved.conversation.id }) { Text("Saved conversation") }
                        }
                    }
                }
            }
        }
        if (state.jobs.isEmpty() && state.pending.isEmpty()) item("empty") { Text("No retained jobs for this gateway yet. Refresh reads the gateway catalog when it is available.") }
        if (state.jobs.size >= state.limit && state.limit < 5000) item("more") { OutlinedButton(onClick = model::loadMore) { Text("Load more retained jobs") } }
    }
}
