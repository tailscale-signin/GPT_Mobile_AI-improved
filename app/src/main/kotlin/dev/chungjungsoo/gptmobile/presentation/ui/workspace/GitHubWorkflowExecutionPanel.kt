package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GitHubProgrammerState

internal data class GitHubRecoveryRequest(val chatId: Int, val runId: String, val action: TaskRecoveryAction)

@Composable
internal fun GitHubWorkflowExecutionPanel(
    state: GitHubProgrammerState,
    onRecovery: (GitHubRecoveryRequest) -> Unit,
    onExport: () -> Unit,
    model: GitHubWorkflowViewModel = hiltViewModel()
) {
    LaunchedEffect(state.chatId) { model.bind(state.chatId) }
    val evidence by model.evidence.collectAsStateWithLifecycle()
    GitHubWorkflowExecutionContent(state, evidence, onRecovery, onExport)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun GitHubWorkflowExecutionContent(
    state: GitHubProgrammerState,
    evidence: GitHubWorkflowEvidence,
    onRecovery: (GitHubRecoveryRequest) -> Unit,
    onExport: () -> Unit
) {
    val latestMessageRun = state.messages.lastOrNull { it.currentRunId != null }?.currentRunId
    val run = state.runs.firstOrNull { it.runId == latestMessageRun }
        ?: state.runs.maxWithOrNull(compareBy({ it.createdAt }, { it.startedAt ?: 0 }, { it.runId }))
    val snapshot = remember(run, state.events, state.eventCountsByRun, evidence) {
        run?.let { GitHubWorkflowPresentation.snapshot(it, state.events, state.events.count { event -> event.runId == it.runId } == (state.eventCountsByRun[it.runId] ?: 0), evidence.verifications[it.runId], it.runId in evidence.unreadableRuns) }
    }
    var details by remember(run?.runId) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth().testTag("github-workflow-execution"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workflow execution", style = MaterialTheme.typography.titleMedium)
            if (snapshot == null) {
                Text("Send a request in Chat to track its execution and acceptance evidence.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${snapshot.phase} · Acceptance: ${snapshot.acceptance.label}", style = MaterialTheme.typography.bodyLarge, color = acceptanceColor(snapshot.acceptance))
                Text(
                    GitHubAcceptanceStatus.entries.joinToString(" · ") { status -> "${snapshot.criteria.count { it.status == status }} ${status.label.lowercase()}" },
                    style = MaterialTheme.typography.bodySmall
                )
                snapshot.criteria.take(3).forEach { criterion ->
                    Text("${criterion.status.label}: ${criterion.requirement}", style = MaterialTheme.typography.bodyMedium, color = acceptanceColor(criterion.status), maxLines = 3)
                }
                if (snapshot.criteria.size > 3) Text("${snapshot.criteria.size - 3} more criteria in Details", style = MaterialTheme.typography.bodySmall)
                snapshot.operations.takeLast(3).forEach { group ->
                    Text("${group.label}: ${group.observed} operations · ${group.completed} completed · ${group.failed} failed", style = MaterialTheme.typography.bodySmall)
                }
                if (!snapshot.eventWindowComplete) Text("Activity shows ${snapshot.observedOperations} of ${state.eventCountsByRun[snapshot.runId] ?: 0} recorded operations for this run.", style = MaterialTheme.typography.bodySmall)
                if (snapshot.recovery.isNotEmpty() || snapshot.failureCategory != null) Text(snapshot.recoveryExplanation, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.recovery.forEach { action ->
                        OutlinedButton(
                            onClick = { state.chatId?.let { onRecovery(GitHubRecoveryRequest(it, snapshot.runId, action)) } },
                            enabled = state.chatId != null && state.approvals.isEmpty() && !state.submitting && state.pending.isEmpty()
                        ) { Text(if (action == TaskRecoveryAction.CONTINUE_RUN) "Resume" else "Retry failed stage") }
                    }
                    TextButton(onClick = { details = true }) { Text("View details") }
                }
            }
        }
    }
    if (details && snapshot != null) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("Workflow details") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Execution: ${snapshot.phase}\nAcceptance: ${snapshot.acceptance.label}")
                    snapshot.failureCategory?.let { Text("Failure category: $it", color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Saved verification: ${if (snapshot.verificationAvailable) "available" else "not available"}\nEvidence retention: ${when (snapshot.evidenceComplete) {
                            true -> "complete for the saved verification"
                            false -> "incomplete or unreadable"
                            null -> "not confirmed"
                        }}"
                    )
                    if (evidence.limited) Text("The evidence index is bounded to its most recent entries. Export the incident for retained history.")
                    snapshot.criteria.forEach { criterion ->
                        Text("${criterion.status.label}: ${criterion.requirement}\n${criterion.detail}")
                    }
                    Text("Observed operations: ${snapshot.observedOperations}${if (snapshot.eventWindowComplete) "" else " in the current activity window"}")
                    snapshot.operations.forEach { group ->
                        Text("${group.label}: ${group.observed} total, ${group.completed} completed, ${group.failed} failed, ${group.pending} pending")
                    }
                    if (snapshot.capabilities.isNotEmpty()) {
                        Text("Tool readiness", style = MaterialTheme.typography.titleSmall)
                        snapshot.capabilities.forEach { capability ->
                            fun observed(value: Boolean?) = when (value) {
                                true -> "yes"
                                false -> "no"
                                null -> "unknown"
                            }
                            Text("${capability.label}\nDiscovered: ${observed(capability.discovered)} · Authorization: ${capability.authorization.lowercase().replace('_', ' ')}\nAvailability: ${capability.availability.lowercase()} · Tested: ${observed(capability.tested)} · Successful test: ${observed(capability.successfullyTested)}")
                        }
                    }
                    Text(snapshot.recoveryExplanation)
                    Text("Run: ${snapshot.runId}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    details = false
                    onExport()
                }) { Text("Export incident") }
            }
        )
    }
}

@Composable
private fun acceptanceColor(status: GitHubAcceptanceStatus) = when (status) {
    GitHubAcceptanceStatus.PASS -> MaterialTheme.colorScheme.primary
    GitHubAcceptanceStatus.FAIL -> MaterialTheme.colorScheme.error
    GitHubAcceptanceStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary
    GitHubAcceptanceStatus.NOT_TESTED -> MaterialTheme.colorScheme.onSurfaceVariant
}
