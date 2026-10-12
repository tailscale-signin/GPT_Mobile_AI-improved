package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class DelegationActivityViewModel @Inject constructor(val sessions: ResearchSessionStore, val runs: AgentRunCoordinator, val history: dev.chungjungsoo.gptmobile.data.delegation.DelegationActivityStore) : ViewModel()

@Composable
internal fun DelegationActivityPanel(model: DelegationActivityViewModel = hiltViewModel()) {
    val controls by dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.modes.collectAsStateWithLifecycle()
    val active by model.runs.activeRuns.collectAsStateWithLifecycle()
    val sessions by model.sessions.sessions.collectAsStateWithLifecycle()
    val history by model.history.runs.collectAsStateWithLifecycle()
    val collect by model.history.enabled.collectAsStateWithLifecycle()
    val unavailable by model.history.unavailable.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var notice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var clear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun operation(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "Could not update activity history"
            }
        }
    }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(model.history.export()) } ?: error("Export unavailable")
                    }
                    notice = "Activity exported"
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = "Could not export activity"
                }
            }
        }
    }
    Column {
        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_active_responses_30e51))
        active.values.filter { it.runId in controls }.forEach { run ->
            val mode = controls[run.runId]
            SettingsPanel("Conversation ${run.chatId}") {
                Text("${mode?.name?.lowercase()?.replace('_', ' ')} · Changes take effect before the next helper request. Completed evidence is retained.")
                if (mode != dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.Mode.PRIMARY_ONLY) {
                    TextButton(onClick = { if (mode == dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.Mode.PAUSED) dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.resume(run.runId) else dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.pause(run.runId) }) { Text(if (mode == dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.Mode.PAUSED) "Resume helper" else "Pause at next request") }
                    TextButton(onClick = { dev.chungjungsoo.gptmobile.data.delegation.DelegationRunControls.primaryOnly(run.runId) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_continue_with_primary_e867e)) }
                }
                TextButton(onClick = { model.runs.cancelChat(run.chatId) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_stop_entire_response_57b35)) }
            }
        }
        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_research_activity_current_session_662fa))
        if (sessions.isEmpty()) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_no_runs_in_this_session_open_a_conversation_t_2b7be))
        sessions.values.sortedByDescending { it.snapshot.updatedAt }.take(20).forEach { run ->
            SettingsPanel(if (run.snapshot.complete) "Completed research" else run.snapshot.phase) {
                Text("${run.snapshot.sources.size} retained sources · ${run.snapshot.searches} searches · ${run.snapshot.engineFailures} engine failures")
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_source_coverage_is_not_a_guarantee_of_accurac_a5c80))
                if (!run.snapshot.complete) {
                    TextButton(onClick = { model.sessions.stop(run.runId) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_stop_acquisition_keep_completed_evidence_0eca3)) }
                    TextButton(onClick = { model.runs.cancelChat(run.chatId) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_stop_entire_response_57b35)) }
                }
            }
        }
        SettingsPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_delegation_history)) {
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_delegation_history_description))
            androidx.compose.material3.Switch(collect, { value -> operation { model.history.setEnabled(value) } })
            if (unavailable) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_delegation_history_unavailable))
            notice?.let { Text(it) }
            TextButton(onClick = { export.launch("delegation-activity-v1.json") }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_export_delegation_history)) }
            TextButton(onClick = { clear = true }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_clear_delegation_history)) }
        }
        history.values.sortedByDescending { it.startedAt }.take(50).forEach { run ->
            SettingsPanel("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(run.startedAt))} · ${run.status.name.lowercase().replace('_', ' ')}") {
                Text("Wall time: ${run.finishedAt?.let { "${(it - run.startedAt).coerceAtLeast(0)} ms" } ?: "In progress"} · Policy ${run.policyRevision.take(12)}")
                val steps = run.events.filter { it.kind == dev.chungjungsoo.gptmobile.data.delegation.DelegationEventKind.STEP_FINISHED }
                dev.chungjungsoo.gptmobile.data.delegation.DelegationRole.entries.filter { role -> steps.any { it.role == role } }.forEach { role ->
                    val roleSteps = steps.filter { it.role == role }
                    Text("${role.name.lowercase()}: ${roleSteps.size} requests · ${roleSteps.mapNotNull { it.elapsedMs }.sum()} ms summed duration")
                    val reported = roleSteps.filter { it.usageProvenance == "provider-reported" }
                    Text(if (reported.isEmpty()) "Token usage unavailable" else "Provider-reported: ${reported.mapNotNull { it.inputTokens }.sum()} input · ${reported.mapNotNull { it.outputTokens }.sum()} output · ${reported.size}/${roleSteps.size} requests with usage")
                }
                run.events.lastOrNull { it.kind == dev.chungjungsoo.gptmobile.data.delegation.DelegationEventKind.REVIEW_FINISHED }?.let { Text("Review: ${it.reviewVerdict} · ${it.reviewScore}/100 · Model assessment") }
                if (run.omittedEvents > 0) Text("${run.omittedEvents} older events omitted; displayed step totals cover retained events.")
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_delegation_output_not_verified))
            }
        }
    }
    if (clear) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { clear = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_clear_delegation_history)) }, text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_clear_delegation_description)) }, confirmButton = {
            TextButton(onClick = {
                operation { model.history.clear() }
                clear = false
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_clear_83b12)) }
        }, dismissButton = { TextButton(onClick = { clear = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_cancel_19766)) } })
    }
}
