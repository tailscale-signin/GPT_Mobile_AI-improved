package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunTerminalError
import kotlinx.coroutines.delay

@Composable
fun RunNoticeChips(notices: List<String>, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    Column(modifier = modifier) {
        notices.forEach { notice ->
            Card(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .semantics { contentDescription = notice },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun AgentRunStatusBlock(run: AgentRun?, modifier: Modifier = Modifier) {
    if (run == null ||
        run.status == AgentRunStatus.COMPLETED ||
        run.status == AgentRunStatus.QUEUED
    ) {
        return
    }

    if (run.status == AgentRunStatus.RUNNING) {
        val startedAt = run.startedAt ?: (System.currentTimeMillis() / 1000)
        var elapsedSeconds by remember(run.runId) {
            mutableLongStateOf((System.currentTimeMillis() / 1000 - startedAt).coerceAtLeast(0))
        }

        LaunchedEffect(run.runId, startedAt) {
            while (true) {
                delay(1000L)
                elapsedSeconds = (System.currentTimeMillis() / 1000 - startedAt).coerceAtLeast(0)
            }
        }

        val elapsedText = formatElapsedDuration(elapsedSeconds)
        Card(
            modifier = modifier.semantics {
                contentDescription = "Running $elapsedText"
            },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Running · $elapsedText",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val status = dev.chungjungsoo.gptmobile.data.agent.gatewayRunStopState(run)?.label ?: when (run.status) {
        AgentRunStatus.PARTIAL -> stringResource(R.string.agent_run_partial)
        AgentRunStatus.EVIDENCE_MISSING -> stringResource(R.string.agent_run_evidence_missing)
        AgentRunStatus.BLOCKED -> stringResource(R.string.agent_run_blocked)
        AgentRunStatus.CANCELED -> stringResource(R.string.agent_run_canceled)
        AgentRunStatus.INTERRUPTED -> stringResource(R.string.agent_run_interrupted)
        else -> stringResource(R.string.agent_run_failed)
    }
    val duration = agentRunDurationSeconds(run)
        ?.let { " · ${pluralStringResource(R.plurals.duration_seconds, it.toInt(), it)}" }
        .orEmpty()
    val terminalError = run.terminalError
        ?.takeIf { it.isNotBlank() }
        ?.let { agentRunTerminalErrorText(it) }

    Card(
        modifier = modifier.semantics { contentDescription = listOfNotNull(status + duration, terminalError).joinToString(". ") },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status + duration,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            terminalError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun formatElapsedDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) {
        "${mins}m ${secs}s"
    } else {
        "${secs}s"
    }
}

@Composable
private fun agentRunTerminalErrorText(error: String): String = when (error) {
    AgentRunTerminalError.SERVICE_STOPPED -> stringResource(R.string.agent_run_error_service_stopped)
    else -> agentRunUserFacingError(error) ?: error
}

/** Recovery guidance never claims an unknown remote write was safely retried. */
internal fun agentRunUserFacingError(error: String): String? {
    val code = error.trim().substringBefore(':').substringBefore(' ').substringBefore('\n')
    return when (code) {
        "CONTEXT_CAPACITY_EXCEEDED", "INPUT_CAPACITY_EXCEEDED" ->
            "This request exceeds the selected model's input capacity. Reduce optional context or choose a route with a verified larger window."
        "OUTPUT_CAPACITY_EXCEEDED" ->
            "The requested reply is larger than the model allows. Reduce the output allowance or select a compatible model."
        "PROVIDER_RATE_LIMITED" ->
            "The selected provider is rate limited. Retry after its recorded cooldown; other model routes may still work."
        "GITHUB_TASK_INCOMPLETE", "COMBINED_REQUIREMENTS_UNMET" ->
            "The task has not met all requirements. Review the saved progress and continue from verified checkpoints."
        "GITHUB_EVIDENCE_REQUIRED" ->
            "The requested verification evidence is incomplete. Inspect the missing checks before marking this task done."
        "GITHUB_VERIFICATION_INTERNAL_ERROR" ->
            "The final verification stopped unexpectedly. Review the saved findings and diagnostic details; task completion has not been confirmed."
        "PROVIDER_WAIT_OUTCOME_UNKNOWN" ->
            "Saved work includes an unconfirmed tool outcome. Reconcile that operation before continuing with another write."
        in dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.CODES ->
            "The saved recovery state changed or is unavailable. Review the current task's recovery details and retained evidence before continuing."
        "DISPATCH_OUTCOME_UNKNOWN" ->
            "The tool outcome is unconfirmed. Reconcile the original remote operation before attempting another write."
        "INVALID_ARGUMENTS" ->
            "A tool rejected the request parameters. Review the tool's supported fields before retrying."
        "GATEWAY_ENGINEERING_GITHUB_REQUIRED" ->
            "The selected Gateway cannot access GitHub. Connect and verify GitHub on that Gateway before resuming."
        "COMPLETION_REPAIR_BUDGET_EXHAUSTED" ->
            "The answer could not be completed within this model's output budget. Continue from the saved partial result."
        else -> null
    }
}

internal fun agentRunDurationSeconds(run: AgentRun): Long? {
    val startedAt = run.startedAt ?: return null
    val completedAt = run.completedAt ?: return null
    return (completedAt - startedAt).coerceAtLeast(0)
}
