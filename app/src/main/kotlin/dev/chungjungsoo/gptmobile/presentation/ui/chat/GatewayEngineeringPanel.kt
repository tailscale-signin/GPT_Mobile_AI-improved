package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
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
    var details by remember { mutableStateOf<GatewayEngineeringBinding?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var wantedRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var wantedOutput by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val saveOutput = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val binding = state.bindings.firstOrNull { it.request.requestId == wantedRequest }
        val output = binding?.job?.workspaceOutputs?.singleOrNull { it.toString() == wantedOutput }
        wantedRequest = null
        wantedOutput = null
        if (binding != null && output != null && destination != null) model.exportOutput(binding, output, appContext, destination)
    }
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
                        job?.let { Text(it.ownershipLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Text("Stage: ${recovery.stage} · Profile: ${recovery.profileUid}", style = MaterialTheme.typography.bodySmall)
                        Text("Requested model: ${recovery.requestedModel}\nObserved model: ${recovery.observedModel}", style = MaterialTheme.typography.bodySmall)
                        GatewayEngineeringBudgetStatus(binding)
                        job?.batchProgress?.let { progress ->
                            Text(
                                progress.readableLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("github_full_batch_progress")
                            )
                            progress.currentState?.let {
                                Text(
                                    it.replace('_', ' '),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
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
                                    Text(if (unread > 0) "Workspace ($unread new)" else "Workspace")
                                }
                                TextButton(onClick = { model.checkpoints(binding) }, enabled = !state.busy) { Text("Checkpoints") }
                                job.prUrl?.takeIf { it.startsWith("https://") }?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Review PR") } }
                                TextButton(onClick = { model.check(binding) }, enabled = !state.busy) { Text("Check saved job") }
                                if (recovery.canCancel) TextButton(onClick = { model.control(binding, job, "cancel") }, enabled = !state.busy) { Text("Stop") }
                            }
                            if (job.mergeReady) {
                                Text("Review the exact merge approval at the bottom of this conversation.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    details?.let { selected ->
        val current = state.bindings.firstOrNull { it.request.requestId == selected.request.requestId } ?: selected
        val recovery = gatewayRecoveryPresentation(current)
        var tab by remember(current.request.requestId) { mutableStateOf("Activity") }
        var updatePage by remember(current.request.requestId) { androidx.compose.runtime.mutableIntStateOf(0) }
        val lastUpdatePage = (current.updates.size - 1).coerceAtLeast(0) / 20
        AlertDialog(
            onDismissRequest = {
                model.markSeen(current)
                details = null
            },
            title = { Text("Coding workspace") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Plan", "Changes", "Tests", "Activity").forEach { section ->
                            androidx.compose.material3.FilterChip(selected = tab == section, onClick = { tab = section }, label = { Text(section) })
                        }
                    }
                    Text("${current.request.taskScope.replaceFirstChar { it.uppercase() }} · ${current.request.repository} · ${current.job?.headSha?.take(12) ?: "Awaiting revision"}")
                    when (tab) {
                        "Plan" -> {
                            Text(current.request.objective)
                            current.job?.workspacePlan?.takeIf(String::isNotBlank)?.let { Text(it) }
                            current.job?.workspaceSteps.orEmpty().forEach { step ->
                                Text("${if (step["done"]?.jsonPrimitive?.content == "true") "✓" else "○"} ${step["name"]?.jsonPrimitive?.content.orEmpty()}")
                            }
                            current.request.acceptance.forEach { item -> Text("${item.id} · ${item.checks.joinToString()}") }
                            current.job?.workspaceNextAction?.let { Text("Next action · $it") }
                            if (current.job?.conversationId != null && current.job?.terminal == false && current.job?.mergeReady == false) {
                                var edit by remember(current.request.requestId) { mutableStateOf("") }
                                val command = androidx.compose.runtime.saveable.rememberSaveable(current.request.requestId, edit) { java.util.UUID.randomUUID().toString() }
                                androidx.compose.material3.OutlinedTextField(edit, { edit = it.take(16000) }, label = { Text("Update the plan or acceptance criteria") })
                                TextButton(enabled = edit.isNotBlank() && !state.busy, onClick = { model.queuePlanEdit(current, edit.trim(), command) }) { Text("Queue instruction") }
                            }
                        }
                        "Changes" -> {
                            val changed = current.job?.changedFiles.orEmpty()
                            if (changed.isEmpty()) Text("No changed files recorded yet.")
                            changed.forEach { Text(it) }
                            TextButton(onClick = { model.checkpoints(current) }, enabled = !state.busy) { Text("Inspect candidate diffs and checkpoints") }
                            current.job?.activeInstructionFiles.orEmpty().forEach { Text("Instructions · $it") }
                        }
                        "Tests" -> {
                            val checks = current.job?.tests.orEmpty() + current.job?.workspaceTests.orEmpty()
                            if (checks.isEmpty()) Text("No test receipts recorded yet.")
                            checks.forEach { check ->
                                Text("${check["name"]?.jsonPrimitive?.content.orEmpty()} · ${check["status"]?.jsonPrimitive?.content.orEmpty()}")
                                check["revision"]?.jsonPrimitive?.content?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            current.job?.workspaceOutputs.orEmpty().forEach { output ->
                                val path = output["path"]?.jsonPrimitive?.content.orEmpty()
                                val status = output["state"]?.jsonPrimitive?.content
                                Text("$path · $status", style = MaterialTheme.typography.bodySmall)
                                output["sha256"]?.jsonPrimitive?.content?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                if (status == "RETAINED") {
                                    TextButton(enabled = !state.busy, onClick = {
                                        wantedRequest = current.request.requestId
                                        wantedOutput = output.toString()
                                        saveOutput.launch(path.substringAfterLast('/'))
                                    }) { Text("Save ${path.substringAfterLast('/')}") }
                                }
                            }
                            current.job?.completionVerification?.let { Text(it.toString()) }
                        }
                        else -> {
                            current.job?.workspaceWorkers.orEmpty().forEach { worker ->
                                Text("${worker["role"]?.jsonPrimitive?.content} · ${worker["id"]?.jsonPrimitive?.content} · ${worker["state"]?.jsonPrimitive?.content} (${worker["calls"]}/${worker["budget"]})")
                            }
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
    state.checkpoints?.let { (binding, history) ->
        var baseline by remember(history.jobId, history.revision) { mutableStateOf<String?>(null) }
        var selected by remember(history.jobId, history.revision) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = model::closeCheckpoints,
            title = { Text("Patch checkpoints") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("${history.state} · ${history.headSha?.take(12).orEmpty()}")
                    history.candidates.forEach { candidate ->
                        TextButton(onClick = { selected = candidate.checkId }) {
                            Text("${if (selected == candidate.checkId) "Selected · " else ""}Check ${candidate.checkIndex}: ${candidate.status} · ${candidate.patchIdentity.take(12)}")
                        }
                    }
                    TextButton(onClick = { selected?.let { model.inspectCandidate(binding, it) } }, enabled = selected != null && !state.busy) { Text("Inspect patch and test output") }
                    if (history.candidates.size >= 2) {
                        TextButton(enabled = selected != null && !state.busy, onClick = { baseline = selected }) { Text("Use selected candidate as baseline") }
                        baseline?.let { first ->
                            Text("Baseline · ${first.take(12)}")
                            TextButton(enabled = selected != null && selected != first && !state.busy, onClick = { selected?.let { model.inspectCandidate(binding, it, left = first) } }) { Text("Compare selected candidate with baseline") }
                        }
                    }
                    if (history.candidates.isEmpty()) Text("No retained patch attempts yet.")
                    Text("Restore reruns validation and review. Published history remains unchanged.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { selected?.let { model.restoreCandidate(binding, it) } },
                    enabled = !state.busy && selected != null && !history.published && history.state == "NEEDS_INPUT" && history.revision == binding.job?.revision
                ) { Text("Restore selected candidate") }
            },
            dismissButton = { TextButton(onClick = model::closeCheckpoints) { Text("Close") } }
        )
    }
    state.evidencePreview?.let { receipt ->
        AlertDialog(
            onDismissRequest = model::closeEvidence,
            title = { Text("Gateway evidence receipt") },
            text = {
                Column {
                    state.candidateCursor?.let { cursor ->
                        Row {
                            TextButton(enabled = !state.busy && cursor.offset > 0, onClick = { model.inspectCandidate(cursor.binding, cursor.checkId, (cursor.offset - 16000).coerceAtLeast(0), cursor.left) }) { Text("Previous page") }
                            TextButton(enabled = !state.busy && cursor.next != null, onClick = { cursor.next?.let { model.inspectCandidate(cursor.binding, cursor.checkId, it, cursor.left) } }) { Text("Next page") }
                        }
                    }
                    androidx.compose.runtime.key(state.candidateCursor?.offset) {
                        Text(receipt, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
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

/** Restored from the durable job; authority remains bound to its current PR/head/revision. */
@Composable
internal fun GatewayMergeInteractions(chatId: Int, model: GatewayEngineeringViewModel = hiltViewModel()) {
    GatewayEngineeringObservation(model, chatId)
    val state by model.state.collectAsStateWithLifecycle()
    state.bindings.filter { it.request.chatId == chatId && it.job?.mergeReady == true }.forEach { binding ->
        val job = requireNotNull(binding.job)
        androidx.compose.runtime.key(job.id, job.revision, job.headSha) {
            GitHubPendingPrompt {
                ChatInteractionSurface(
                    inline = true,
                    onDismissRequest = {},
                    title = { Text("Merge PR #${job.pr}?") },
                    text = {
                        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(job.repository)
                            Text(job.headSha.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("This approval applies only to this PR and commit, expires after 30 minutes, and is rejected if the head, base or merge policy changes.")
                            Text("Changed files\n" + job.changedFiles.joinToString("\n"))
                            Text("Test evidence\n" + job.tests.joinToString("\n") { "${it["name"]}: ${it["status"]}" })
                            Text("Completion scope: ${job.completionVerification?.get("scope") ?: "Review the requested requirements."}")
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    confirmButton = { Button(onClick = { model.control(binding, job, "approve") }, enabled = !state.busy) { Text("Approve & Merge") } },
                    dismissButton = { OutlinedButton(onClick = { model.control(binding, job, "request_changes") }, enabled = !state.busy) { Text("Request changes") } }
                )
            }
        }
    }
}
