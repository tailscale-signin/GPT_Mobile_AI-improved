package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopOutcome
import dev.chungjungsoo.gptmobile.data.github.gatewayElapsedMillis
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog
import kotlinx.coroutines.delay

@Composable
internal fun GatewayJobsObservation(model: GatewayJobsViewModel, profileUid: String?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(profileUid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.visible(profileUid) }
    }
}

@Composable
internal fun GatewayJobsScreen(model: GatewayJobsViewModel, onOpenConversation: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                elapsedNow = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    val fresh = state.error == null && state.observedAtElapsed > 0 && elapsedNow - state.observedAtElapsed <= 30_000
    val displayedRows = if (state.legacy) state.legacyRows else state.rows
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.gateway_jobs_title), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { model.requestRefresh() }, enabled = !state.loading && state.configured) { Text(stringResource(R.string.gateway_jobs_refresh)) }
                }
                Text(state.connectionName, style = MaterialTheme.typography.labelLarge)
                if (state.principalId == "network:shared") Text(stringResource(R.string.gateway_jobs_shared_scope), style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        state.legacy -> stringResource(R.string.gateway_jobs_legacy)
                        fresh -> stringResource(R.string.gateway_jobs_live)
                        state.syncedAtMillis > 0 -> stringResource(R.string.gateway_jobs_last_synced, java.time.Instant.ofEpochMilli(state.syncedAtMillis).toString())
                        state.error != null -> stringResource(R.string.gateway_jobs_unavailable)
                        else -> stringResource(R.string.gateway_jobs_loading)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.legacyRows.isNotEmpty()) {
                    TextButton(onClick = { if (state.legacy) model.closeLegacyEvidence() else model.showLegacyEvidence() }) {
                        Text(stringResource(if (state.legacy) R.string.gateway_jobs_current_account else R.string.gateway_jobs_local_evidence))
                    }
                }
                if (state.instructionQueued) Text(stringResource(R.string.gateway_jobs_instruction_queued), style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                state.cachedChatId?.let { chatId ->
                    OutlinedButton(onClick = {
                        model.dismissCached()
                        onOpenConversation(chatId)
                    }) { Text(stringResource(R.string.gateway_jobs_open_cached)) }
                }
            }
        }
        if (!state.legacy && state.rows.isEmpty() && state.loaded && state.inventoryComplete && state.error == null) {
            item("empty") { Text(stringResource(R.string.gateway_jobs_empty), style = MaterialTheme.typography.bodyLarge) }
        }
        for (group in GatewayJobGroup.entries) {
            val grouped = displayedRows.filter { gatewayJobDisplayGroup(it.summary.schedulingState ?: it.summary.state, it.summary.phase) == group }
            val rows = if (group == GatewayJobGroup.QUEUED) grouped.sortedBy { it.summary.acceptedAt } else grouped.sortedByDescending { it.summary.acceptedAt }
            if (rows.isEmpty()) continue
            item("section:$group") {
                Text(
                    stringResource(
                        when (group) {
                            GatewayJobGroup.RUNNING -> R.string.gateway_jobs_running
                            GatewayJobGroup.QUEUED -> R.string.gateway_jobs_queued
                            GatewayJobGroup.NEEDS_ATTENTION -> R.string.gateway_jobs_attention
                            GatewayJobGroup.RECENT -> R.string.gateway_jobs_recent
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(rows, key = { it.record.key }) { row ->
                val job = row.summary
                val rowFresh = fresh && row.observedAtElapsed > 0 && elapsedNow - row.observedAtElapsed <= 30_000
                val timer = gatewayElapsedMillis(job.acceptedAt, job.finishedAt, job.serverNow ?: state.serverNow, if (row.observedAtElapsed > 0) (elapsedNow - row.observedAtElapsed).coerceIn(0, 30_000) else 0)
                Card(Modifier.fillMaxWidth().clickable(enabled = state.opening == null) { model.open(row, onOpenConversation) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(job.displayTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (job.revision > row.record.seenRevision) Text(stringResource(R.string.gateway_jobs_new_updates), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(state.profileNames[job.profileId] ?: job.model.ifBlank { job.profileId ?: stringResource(R.string.gateway_jobs_saved_profile) }, style = MaterialTheme.typography.labelMedium)
                        if (state.legacy) Text(stringResource(R.string.gateway_jobs_legacy_account, row.record.principalId, row.record.gatewayId), style = MaterialTheme.typography.bodySmall)
                        Text(job.phase?.takeIf(String::isNotBlank)?.let(::gatewayPhaseLabel) ?: gatewaySchedulingLabel(job.state.lowercase(java.util.Locale.ROOT)), style = MaterialTheme.typography.bodySmall)
                        job.batchProgress?.let { progress ->
                            Text(
                                progress.readableLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("gateway_full_batch_progress")
                            )9196
                            progress.currentState?.let {
                                Text(
                                    it.replace('_', ' ').replaceFirstChar(Char::uppercase),
                                    style = MaterialTheme.typography.bodySmall
                                )9493
                            }
                        }
                        if (job.repository != null) {
                            val source = job.sourceBranch ?: job.branch ?: job.sourceSha?.take(12)
                            Text(listOfNotNull(job.repository, source).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            job.workingBranch?.takeIf { it != source }?.let { Text(stringResource(R.string.gateway_jobs_working_branch, it), style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(stringResource(R.string.gateway_jobs_total, gatewayElapsedLabel(timer)), style = MaterialTheme.typography.labelLarge)
                        if (!rowFresh && job.finishedAt == null) Text(stringResource(R.string.gateway_jobs_cached_state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.record.key in state.partialHistoryKeys) Text(stringResource(R.string.gateway_transcript_history_partial), style = MaterialTheme.typography.bodySmall)
                        if (job.group == GatewayJobGroup.QUEUED) {
                            val formatter = android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current)
                            val waitLabel = job.providerRetryAt?.takeIf { it.isFinite() && it > 0 }
                                ?.let { stringResource(R.string.provider_wait_retry_at, formatter.format(java.util.Date((it * 1000).toLong()))) }
                            val queueText = waitLabel ?: job.queuePosition?.let { stringResource(R.string.gateway_jobs_queue_position, it) } ?: job.queueReason
                            queueText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (!state.legacy && job.acceptsInstruction) {
                            TextButton(onClick = { model.showInstruction(row) }) { Text(stringResource(R.string.gateway_jobs_instruction)) }
                        }
                        if (!state.legacy && ("cancel" in job.allowedActions || job.cancellationRequested || job.phase.equals("CANCELLING", ignoreCase = true))) {
                            TextButton(onClick = { model.showStop(row) }, enabled = state.stop?.busy != true) {
                                Text(stringResource(if (job.cancellationRequested || job.phase.equals("CANCELLING", ignoreCase = true)) R.string.gateway_job_stop_check else R.string.gateway_job_stop))
                            }
                        }
                        if (state.opening == row.record.key) Text(stringResource(R.string.gateway_jobs_opening), style = MaterialTheme.typography.bodySmall)
                        if (job.conversationId != null && !state.legacy) {
                            TextButton(onClick = { model.showTranscript(row) }, enabled = state.readingTranscript == null) { Text(stringResource(R.string.gateway_jobs_saved_transcript)) }
                        }
                    }
                }
            }
        }
        if (!state.legacy && state.hasMore) {
            item("more") {
                OutlinedButton(onClick = { model.requestRefresh(loadMore = true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.gateway_jobs_load_more)) }
            }
        }
    }
    state.transcriptKey?.let { GatewayTranscriptDialog(it, onDismiss = model::dismissTranscript) }
    state.stop?.let { draft ->
        val summary = draft.review?.summary ?: draft.row.summary
        val outcome = draft.result?.outcome
        FadingAlertDialog(
            onDismissRequest = model::dismissStop,
            title = { Text(stringResource(R.string.gateway_job_stop_title)) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(summary.displayTitle, style = MaterialTheme.typography.titleSmall)
                    Text(state.profileNames[draft.row.record.connectionProfileUid] ?: state.connectionName, style = MaterialTheme.typography.labelLarge)
                    Text(listOfNotNull(state.profileNames[summary.profileId] ?: summary.profileId, summary.model.takeIf(String::isNotBlank), summary.repository, summary.branch).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (draft.row.record.principalId == "network:shared") Text(stringResource(R.string.gateway_jobs_shared_scope), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.gateway_job_stop_help), style = MaterialTheme.typography.bodySmall)
                    outcome?.let {
                        Text(
                            stringResource(
                                when (it) {
                                    GatewayJobStopOutcome.REQUESTED -> R.string.gateway_job_stop_requested
                                    GatewayJobStopOutcome.CONFIRMED -> R.string.gateway_job_stop_confirmed
                                    GatewayJobStopOutcome.ALREADY_TERMINAL -> R.string.gateway_job_stop_terminal
                                    GatewayJobStopOutcome.UNKNOWN -> R.string.gateway_job_stop_unknown
                                    GatewayJobStopOutcome.UNAVAILABLE -> R.string.gateway_job_stop_unavailable
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (draft.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.gateway_job_stop_checking), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                when {
                    outcome in setOf(GatewayJobStopOutcome.CONFIRMED, GatewayJobStopOutcome.ALREADY_TERMINAL) -> Unit
                    draft.review == null || outcome in setOf(GatewayJobStopOutcome.REQUESTED, GatewayJobStopOutcome.UNAVAILABLE) -> TextButton(onClick = model::checkStop, enabled = !draft.busy) { Text(stringResource(R.string.gateway_job_stop_check)) }
                    else -> TextButton(onClick = model::confirmStop, enabled = !draft.busy) { Text(stringResource(if (outcome == GatewayJobStopOutcome.UNKNOWN) R.string.gateway_job_stop_retry else R.string.gateway_job_stop_confirm)) }
                }
            },
            dismissButton = { TextButton(onClick = model::dismissStop, enabled = !draft.busy) { Text(stringResource(R.string.gateway_jobs_close)) } }
        )
    }
    state.instruction?.let { draft ->
        FadingAlertDialog(
            onDismissRequest = model::dismissInstruction,
            title = { Text(stringResource(R.string.gateway_jobs_instruction)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(draft.row.summary.displayTitle, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.gateway_jobs_instruction_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = draft.content, onValueChange = model::editInstruction, enabled = !draft.sending && !draft.attempted, label = { Text(stringResource(R.string.gateway_jobs_instruction_text)) }, minLines = 3, maxLines = 8)
                    draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (draft.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = model::sendInstruction, enabled = !draft.sending && draft.content.isNotBlank()) { Text(stringResource(if (draft.attempted) R.string.gateway_jobs_instruction_retry else R.string.gateway_jobs_instruction_send)) } },
            dismissButton = { TextButton(onClick = model::dismissInstruction, enabled = !draft.sending) { Text(stringResource(R.string.gateway_jobs_close)) } }
        )
    }
    state.legacyDetail?.let { binding ->
        FadingAlertDialog(
            onDismissRequest = model::dismissLegacy,
            title = { Text(stringResource(R.string.gateway_jobs_retained_evidence)) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.gateway_jobs_legacy_explanation))
                    Text(binding.request.objective)
                    Text("${binding.request.repository} · ${binding.request.branch}")
                    binding.updates.takeLast(60).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                    binding.job?.finalReport?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = model::dismissLegacy) { Text(stringResource(R.string.gateway_jobs_close)) } }
        )
    }
}

/** IDs preserve saved tabs from older app versions. */
internal enum class GitHubWorkspaceDestination(val id: Int) {
    CHAT(3),
    JOBS(6),
    STATUS(2),
    SETTINGS(5)
}
