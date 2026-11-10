package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.budgetDescription
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import kotlinx.serialization.json.jsonPrimitive

/** Persisted host jobs are shown in the ordinary conversation, including notification deep links. */
@Composable
fun GatewayEngineeringPanel(chatId: Int, context: GitHubConversationContext, profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2?, debugMode: Boolean = false, model: GatewayEngineeringViewModel = hiltViewModel()) {
    GatewayEngineeringObservation(model, chatId)
    val state by model.state.collectAsStateWithLifecycle()
    var page by remember(chatId) { androidx.compose.runtime.mutableIntStateOf(0) }
    val bindings = state.bindings.filter { it.chatId == chatId }
    val lastPage = (bindings.size - 1).coerceAtLeast(0) / 20
    val visible = bindings.asReversed().drop(page.coerceAtMost(lastPage) * 20).take(20)
    var confirmation by remember { mutableStateOf<Pair<GatewayEngineeringBinding, GatewayEngineeringJob>?>(null) }
    var details by remember { mutableStateOf<GatewayEngineeringBinding?>(null) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (bindings.isNotEmpty()) Text("Saved engineering work", style = MaterialTheme.typography.titleSmall)
        if (lastPage > 0) {
            Row {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Newer jobs") }
                TextButton(onClick = { page++ }, enabled = page < lastPage) { Text("Earlier jobs") }
            }
        }
        visible.forEach { binding ->
            androidx.compose.runtime.key(binding.request.requestId) {
                val job = binding.job
                val recovery = gatewayRecoveryPresentation(binding)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${binding.request.repository} · ${job?.branch ?: binding.request.branch}", style = MaterialTheme.typography.titleSmall)
                        Text("Stage: ${recovery.stage} · Profile: ${recovery.profileUid}", style = MaterialTheme.typography.bodySmall)
                        Text("Requested model: ${recovery.requestedModel}\nObserved model: ${recovery.observedModel}", style = MaterialTheme.typography.bodySmall)
                        GatewayEngineeringBudgetStatus(binding)
                        if (binding.profileUid != context.mainProfileUid || profile?.uid != binding.profileUid || profile?.enabled != true) {
                            Text("This job retains its original profile. Its saved progress remains available after a selection change.", style = MaterialTheme.typography.bodySmall)
                        }
                        recovery.reason?.let { Text("Blocked: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (job == null) {
                            Text("Submission has not been confirmed.")
                            Text(recovery.nextAction, style = MaterialTheme.typography.bodySmall)
                            if (recovery.canReconcileRequest) TextButton(onClick = { model.reconnect(binding) }, enabled = !state.busy) { Text("Reconcile saved request") }
                        } else {
                            if (LocalChatProgressInHeader.current) Text(job.message, style = MaterialTheme.typography.bodyMedium) else EngineeringJobProgress(job)
                            Text(recovery.nextAction, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { details = binding }) {
                                    val unread = binding.updates.count { it.revision > binding.seenRevision }
                                    Text(if (unread > 0) "Evidence ($unread new)" else "Evidence")
                                }
                                job.prUrl?.takeIf { it.startsWith("https://") }?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Review PR") } }
                                TextButton(onClick = { model.check(binding) }, enabled = !state.busy) { Text("Check saved job") }
                                if (recovery.canCancel) TextButton(onClick = { model.control(binding, job, "cancel") }, enabled = !state.busy) { Text("Stop") }
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
        val recovery = gatewayRecoveryPresentation(current)
        var updatePage by remember(current.request.requestId) { androidx.compose.runtime.mutableIntStateOf(0) }
        val lastUpdatePage = (current.updates.size - 1).coerceAtLeast(0) / 20
        AlertDialog(
            onDismissRequest = {
                model.markSeen(current)
                details = null
            },
            title = { Text("Engineering updates") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Job ${current.job?.id ?: current.request.requestId}", style = MaterialTheme.typography.bodySmall)
                    Text("Owner: ${current.principalId ?: "Not recorded"}\nProfile: ${recovery.profileUid}\nRepository: ${current.request.repository}\nHead: ${current.job?.headSha ?: "Not observed"}", style = MaterialTheme.typography.bodySmall)
                    Text("Execution identity: ${recovery.executionIdentity}\nHardware: ${recovery.hardware}", style = MaterialTheme.typography.bodySmall)
                    GatewayEngineeringBudgetStatus(current)
                    Text("Check saved job retrieves retained progress on the original route. Confirmed operations and partial output are preserved.")
                    if (lastUpdatePage > 0) {
                        Row {
                            TextButton(onClick = { updatePage-- }, enabled = updatePage > 0) { Text("Newer evidence") }
                            TextButton(onClick = { updatePage++ }, enabled = updatePage < lastUpdatePage) { Text("Earlier evidence") }
                        }
                    }
                    current.updates.asReversed().drop(updatePage.coerceAtMost(lastUpdatePage) * 20).take(20).forEach { update ->
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

/** The same allowance stays visible before admission and with the retained server receipt. */
@Composable
internal fun GatewayEngineeringBudgetStatus(binding: GatewayEngineeringBinding) {
    binding.budgetDescription()?.let { description ->
        Text(description, Modifier.testTag("gateway_engineering_budget"), style = MaterialTheme.typography.bodySmall)
    }
}

/** Multiple mounted surfaces share one observer, and backgrounding stops foreground polling. */
@Composable
internal fun GatewayEngineeringObservation(model: GatewayEngineeringViewModel, chatId: Int?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember { java.util.UUID.randomUUID().toString() }
    DisposableEffect(model, chatId, lifecycle, owner) {
        fun update() {
            if (chatId != null) model.setVisible(owner, chatId, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            if (chatId != null) model.setVisible(owner, chatId, false)
        }
    }
}

@Composable
internal fun EngineeringJobProgress(job: GatewayEngineeringJob, textSizeIncrease: Int = 0) {
    GitHubLoadingHeader(job.message, !job.terminal, (job.createdAt * 1000).toLong(), job.finishedAt?.times(1000)?.toLong(), textSizeIncrease + 3, job.progress)
    Text("${job.branch} · Milestone ${job.milestonesCompleted}/${job.milestonesTotal} · ${job.repairAttempts} repair attempts", style = MaterialTheme.typography.labelSmall)
}
