package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlinx.serialization.json.jsonPrimitive

/** Persisted host jobs are shown in the ordinary conversation, including notification deep links. */
@Composable
fun GatewayEngineeringPanel(chatId: Int, context: GitHubConversationContext, profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2?, debugMode: Boolean = false, model: GatewayEngineeringViewModel = hiltViewModel()) {
    val selectedProfile = profile?.takeIf { it.uid == context.mainProfileUid } ?: return
    if (!dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy.enabled(selectedProfile)) return
    LaunchedEffect(chatId) { model.bind(chatId) }
    val state by model.state.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<Pair<GatewayEngineeringBinding, GatewayEngineeringJob>?>(null) }
    var details by remember { mutableStateOf<GatewayEngineeringBinding?>(null) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (debugMode) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.bindings.filter { it.profileUid == selectedProfile.uid && (debugMode || it.job?.mergeReady == true || it.job?.pr != null || it.job?.terminal == false) }.forEach { binding ->
            val job = binding.job
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (job == null) {
                        Text("Reconnecting your task…")
                        TextButton(onClick = { model.reconnect(binding) }, enabled = !state.busy) { Text("Reconnect saved request") }
                    } else {
                        if (LocalChatProgressInHeader.current) Text(job.message, style = MaterialTheme.typography.bodyMedium) else EngineeringJobProgress(job)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (debugMode) {
                                TextButton(onClick = { details = binding }) {
                                    val unread = binding.updates.count { it.revision > binding.seenRevision }
                                    Text(if (unread > 0) "Updates ($unread new)" else "Updates")
                                }
                            }
                            if (job.pr != null) TextButton(onClick = { uri.openUri("https://github.com/${job.repository}/pull/${job.pr}") }) { Text("Review PR") }
                            if (!job.terminal) TextButton(onClick = { model.control(binding, job, "cancel") }, enabled = !state.busy) { Text("Stop") }
                        }
                        if (job.mergeReady) {
                            Text("All required checks passed. Review the exact commit before merging.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { confirmation = binding to job }, enabled = !state.busy) { Text("Approve & Merge") }
                                OutlinedButton(onClick = { model.control(binding, job, "request_changes") }, enabled = !state.busy) { Text("Request changes") }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (binding, job) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Merge PR #${job.pr}?") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.repository)
                    Text(job.headSha.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("This approval applies only to this PR and commit, expires after 30 minutes, and is rejected if the head, base or merge policy changes.")
                    Text("Changed files\n" + job.changedFiles.joinToString("\n"))
                    Text("Test evidence\n" + job.tests.joinToString("\n") { "${it["name"]}: ${it["status"]}" })
                    Text("Completion scope: ${job.completionVerification?.get("scope") ?: "Feature completeness has not been measured. Review the requested requirements."}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    model.control(binding, job, "approve")
                }) { Text("Approve & Merge") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } }
        )
    }
    details?.let { selected ->
        val current = state.bindings.firstOrNull { it.request.requestId == selected.request.requestId } ?: selected
        AlertDialog(
            onDismissRequest = {
                model.markSeen(current)
                details = null
            },
            title = { Text("Engineering updates") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Job ${current.job?.id ?: current.request.requestId}", style = MaterialTheme.typography.bodySmall)
                    Text("The gateway keeps working while this app is closed. Reconnecting retrieves saved updates; it does not start another job.")
                    current.updates.forEach { update ->
                        Text("${java.time.Instant.ofEpochSecond(update.createdAt.toLong())}\n${update.message}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.updates.isEmpty()) Text(current.job?.message ?: "Connecting to saved request")
                    current.job?.completionVerification?.let { Text("Requirement verification\n$it", style = MaterialTheme.typography.bodySmall) }
                    current.job?.latestEvidence?.get("receipt_id")?.jsonPrimitive?.content?.let { receipt ->
                        TextButton(onClick = { model.evidence(current, receipt) }, enabled = !state.busy) { Text("Inspect latest evidence") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.markSeen(current)
                    details = null
                }) { Text("Close") }
            }
        )
    }
    state.evidencePreview?.let { receipt ->
        AlertDialog(
            onDismissRequest = model::closeEvidence,
            title = { Text("Gateway evidence receipt") },
            text = { Text(receipt, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = model::closeEvidence) { Text("Close") } }
        )
    }
}

@Composable
internal fun EngineeringJobProgress(job: GatewayEngineeringJob, textSizeIncrease: Int = 0) {
    GitHubLoadingHeader(job.message, !job.terminal, (job.createdAt * 1000).toLong(), job.finishedAt?.times(1000)?.toLong(), textSizeIncrease + 3, job.progress)
    Text("${job.branch} · Milestone ${job.milestonesCompleted}/${job.milestonesTotal} · ${job.repairAttempts} repair attempts", style = MaterialTheme.typography.labelSmall)
}
