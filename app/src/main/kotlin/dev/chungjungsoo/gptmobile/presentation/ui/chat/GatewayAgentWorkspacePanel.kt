package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GatewayAgentWorkspace
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspaceCommandReceipt
import dev.chungjungsoo.gptmobile.data.github.workspaceText
import dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubCodeDiff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One source-bound view of the existing job, kept inside the ordinary coding workspace. */
@Composable
internal fun GatewayAgentWorkspaceSection(binding: GatewayEngineeringBinding, section: String, state: GatewayEngineeringUiState, model: GatewayEngineeringViewModel) {
    val observed = state.workspace?.takeIf { state.workspaceRequestId == binding.request.requestId && it.jobId == binding.job?.id }
    val board = observed?.copy(availableActions = observed.availableActions.filter { observed.canRunWithUnresolvedCommands(binding, it) })
    val stale = board != null && board.headSha != binding.job?.headSha
    val pending = binding.workspaceCommand
    val receipt = state.workspaceReceipt?.takeIf { it.commandId == pending?.commandId && it.jobId == pending.jobId } ?: pending?.receipt
    val enabled = !state.busy && !stale && pending?.settled != false
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { model.openWorkspace(binding) }, enabled = !state.busy) { Text(if (board == null) "Load workspace" else "Refresh workspace") }
        if (pending != null) TextButton(onClick = { model.reconcileWorkspaceCommand(binding) }, enabled = !state.busy) { Text("Check saved command") }
        if (pending?.receipt?.status == "NOT_FOUND" && pending.receipt.confirmedAbsent && !pending.dismissedAbsent) {
            TextButton(onClick = { model.retryWorkspaceCommand(binding) }, enabled = !state.busy && board?.headSha == pending.headSha && board.revision == pending.revision) { Text("Retry original command") }
            if (board != null && (board.headSha != pending.headSha || board.revision != pending.revision)) TextButton(onClick = { model.dismissAbsentWorkspaceCommand(binding) }, enabled = !state.busy) { Text("Dismiss stale absent command") }
        }
        if (pending?.receipt?.status == "UNKNOWN" && board?.state == "NEEDS_INPUT") TextButton(onClick = { model.acknowledgeUnknownWorkspaceCommand(binding) }, enabled = !state.busy) { Text("Acknowledge unresolved command") }
    }
    if (pending != null) {
        Text("${pending.action.replace('_', ' ')} · ${if (pending.dismissedAbsent) "confirmed absent · dismissed locally" else pending.receipt?.status?.lowercase() ?: "outcome not confirmed"}", style = MaterialTheme.typography.bodySmall)
        if (!pending.settled) Text("The command identity is saved. Reconnect checks this operation without sending it again.", style = MaterialTheme.typography.bodySmall)
        receipt?.let { GatewayWorkspaceCommandEvidence(it, includeResult = false) }
        if (pending.receipt?.status == "UNKNOWN") Text("Acknowledging keeps the original command and encrypted arguments for readback. It enables safe maintenance while this job waits for review. It never retries the command or confirms success.", style = MaterialTheme.typography.bodySmall)
    }
    if (binding.workspaceUnresolvedCommands.isNotEmpty()) {
        Text("Retained command outcomes", style = MaterialTheme.typography.titleSmall)
        binding.workspaceUnresolvedCommands.forEach { command ->
            GatewayWorkspaceItem("${command.action.replace('_', ' ')} · ${command.receipt?.status ?: "Unconfirmed"}") {
                Text("${command.commandId} · source ${command.headSha.take(12)}", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { model.reconcileWorkspaceCommand(binding, command.commandId) }, enabled = !state.busy) { Text("Check this original command") }
                val retained = state.workspaceReceipt?.takeIf { it.commandId == command.commandId && it.jobId == command.jobId } ?: command.receipt
                retained?.let { GatewayWorkspaceCommandEvidence(it) }
            }
        }
        if (binding.workspaceUnresolvedCommands.any { !it.settled }) Text("An original command is still unresolved. Available maintenance preserves its identity and keeps coding actions blocked.", style = MaterialTheme.typography.bodySmall)
    }
    state.workspaceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (board == null) {
        Text("Open the original Gateway connection to inspect saved workers, agents and source-bound evidence. Phone GitHub review remains available.")
        return
    }
    Text("Source ${board.headSha.take(12)} · observation ${board.revision}", style = MaterialTheme.typography.labelSmall)
    if (stale) Text("This view belongs to the previous source. Refresh before applying changes.", color = MaterialTheme.colorScheme.error)
    when (section) {
        "Workers" -> GatewayWorkerBoard(board, binding, enabled, model)
        "Agents" -> GatewayAgentBackendPanel(board, binding, enabled, model)
        "Review" -> GatewayWorkspaceReview(board, binding, state, enabled, model)
        "Environment" -> GatewayWorkspaceEnvironment(board, binding, enabled, model)
        "Tools" -> GatewayWorkspaceTools(board, binding, enabled, model)
    }
    if (section in setOf("Tools", "Agents", "Environment")) {
        receipt?.result?.let { GatewayWorkspaceReceipt("Latest command evidence", it.toString()) }
        if (receipt?.result == null) receipt?.resultReference?.let { GatewayWorkspaceReceipt("Full result retained as a private artifact", it.toString()) }
    }
}

@Composable
private fun GatewayWorkspaceCommandEvidence(receipt: GatewayWorkspaceCommandReceipt, includeResult: Boolean = true) {
    receipt.reason?.let { Text("Result · ${it.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.bodySmall) }
    receipt.error?.let { GatewayWorkspaceReceipt("Command result", it.toString()) }
    if (includeResult) receipt.result?.let { GatewayWorkspaceReceipt("Latest command evidence", it.toString()) }
    if (includeResult && receipt.result == null) receipt.resultReference?.let { GatewayWorkspaceReceipt("Full result retained as a private artifact", it.toString()) }
}

@Composable
internal fun GatewayWorkspaceReceipt(title: String, value: String) {
    var expanded by remember(title, value) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide $title" else title) }
    if (expanded) SelectionContainer { GitHubCodeDiff(value) }
}

@Composable
internal fun GatewayWorkspaceItem(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun GatewayWorkerBoard(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    Text("Workers · ${board.workers.size} / 8", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gateway_worker_board"))
    if (board.workers.isEmpty()) Text("No workers have been admitted for this job.")
    board.workers.forEach { worker ->
        val id = worker.workspaceText("worker_id") ?: worker.workspaceText("id").orEmpty()
        GatewayWorkspaceItem("${worker.workspaceText("role").orEmpty()} · $id") {
            Text("${worker.workspaceText("state") ?: "Unknown"} · ${worker.workspaceText("calls") ?: "?"}/${worker.workspaceText("budget") ?: "?"} calls")
            worker.workspaceText("objective")?.let { Text(it) }
            listOf("head_sha", "source_sha", "candidate_check_id", "test_status", "blocked_reason", "findings").forEach { key -> worker.workspaceText(key)?.let { Text("${key.replace('_', ' ')} · $it", style = MaterialTheme.typography.bodySmall) } }
            GatewayWorkspaceReceipt("Worker tools and evidence", worker.toString())
            if (board.canRun("worker_wave") && worker.workspaceText("state") == "ACTIVE") {
                TextButton(enabled = enabled, onClick = {
                    model.workspaceCommand(binding, "worker_wave", buildJsonObject { put("workers", JsonArray(listOf(buildJsonObject { put("worker_id", id) }))) })
                }) { Text("Advance saved worker") }
            }
        }
    }
    Text("Isolated terminals · ${board.terminals.size} / 3", style = MaterialTheme.typography.titleMedium)
    if (board.terminals.isEmpty()) Text("No isolated terminal is retained for this job.")
    board.terminals.forEach { terminal ->
        GatewayWorkspaceItem(terminal.workspaceText("session_id") ?: terminal.workspaceText("id") ?: "Retained terminal") {
            listOf("state", "branch", "head_sha", "source_sha", "candidate_check_id", "expires_at").forEach { key -> terminal.workspaceText(key)?.let { Text("${key.replace('_', ' ')} · $it", style = MaterialTheme.typography.bodySmall) } }
            GatewayWorkspaceReceipt("Terminal identity and evidence", terminal.toString())
        }
    }
    if (board.canRun("worker_wave")) {
        var objective by remember(board.jobId, board.headSha) { mutableStateOf("") }
        var roles by remember(board.jobId, board.headSha) { mutableStateOf(setOf("reviewer")) }
        var budget by remember(board.jobId, board.headSha) { mutableStateOf("6") }
        Text("Run independent workers", style = MaterialTheme.typography.titleMedium)
        Text("Workers keep the job's accepted source, selected model and total budget. Each worker retains its own context and candidate.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("planner", "editor", "reviewer", "retrieval", "test worker").forEach { role ->
                FilterChip(selected = role in roles, onClick = { roles = if (role in roles) roles - role else roles + role }, label = { Text(role.replaceFirstChar { it.uppercase() }) })
            }
        }
        OutlinedTextField(objective, { objective = it.take(2000) }, label = { Text("Worker objective within this task") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(budget, { budget = it.take(2) }, label = { Text("Calls per worker (2–12)") }, singleLine = true)
        TextButton(
            enabled = enabled && objective.isNotBlank() && roles.isNotEmpty() && roles.size + board.workers.size <= 8 && budget.toIntOrNull() in 2..12,
            onClick = {
                val rows = roles.sorted().map { role ->
                    buildJsonObject {
                        put("worker_id", "phone-" + java.util.UUID.randomUUID().toString().take(12))
                        put("role", role)
                        put("objective", objective.trim())
                        put("budget", requireNotNull(budget.toIntOrNull()))
                    }
                }
                model.workspaceCommand(binding, "worker_wave", buildJsonObject { put("workers", JsonArray(rows)) })
            }
        ) { Text("Queue ${roles.size} worker${if (roles.size == 1) "" else "s"}") }
    }
}

@Composable
private fun GatewayWorkspaceReview(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, state: GatewayEngineeringUiState, enabled: Boolean, model: GatewayEngineeringViewModel) {
    val history = state.workspaceCandidates?.takeIf { it.jobId == board.jobId && it.headSha == board.headSha }
    var selected by remember(board.jobId, board.headSha) { mutableStateOf<String?>(null) }
    var baseline by remember(board.jobId, board.headSha) { mutableStateOf<String?>(null) }
    var notes by remember(board.jobId, board.headSha, selected) { mutableStateOf("") }
    val draftId = remember(board.jobId, board.headSha, selected) { "review-" + java.util.UUID.randomUUID().toString().take(16) }
    GatewayWorkspaceSourceBrowser(board, binding, state, model)
    Text("Candidate review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gateway_candidate_review"))
    Text("Compare patches and recorded checks at this source. Selecting a candidate does not publish it.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { model.workspaceCandidates(binding) }, enabled = !state.busy) { Text("Load candidate files and evidence") }
    history?.candidates.orEmpty().forEach { candidate ->
        FilterChip(selected = selected == candidate.checkId, onClick = {
            selected = candidate.checkId
            model.workspaceCandidate(binding, candidate.checkId)
        }, enabled = !state.busy && candidate.sourceSha == board.headSha, label = { Text("${candidate.checkIndex?.let { "Check $it" } ?: "Unvalidated candidate"} · ${candidate.status}") })
    }
    if (history != null && history.candidates.isEmpty()) Text("No candidate patches have been retained.")
    selected?.let { checkId ->
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { baseline = checkId }, enabled = !state.busy) { Text("Set comparison baseline") }
            if (baseline != null && baseline != checkId) TextButton(onClick = { model.workspaceCandidate(binding, checkId, left = baseline) }, enabled = !state.busy) { Text("Compare with baseline") }
        }
        baseline?.let { Text("Comparison baseline ${it.take(12)}", style = MaterialTheme.typography.labelSmall) }
        state.workspaceCandidate?.takeIf { it.checkId == checkId && it.binding.job?.headSha == board.headSha }?.let { cursor ->
            Row {
                TextButton(onClick = { model.workspaceCandidate(binding, checkId, (cursor.offset - 16000).coerceAtLeast(0), cursor.left) }, enabled = !state.busy && cursor.offset > 0) { Text("Previous source page") }
                TextButton(onClick = { cursor.next?.let { model.workspaceCandidate(binding, checkId, it, cursor.left) } }, enabled = !state.busy && cursor.next != null) { Text("Next source page") }
            }
            state.workspaceDiff?.let { GitHubCodeDiff(it) }
        }
        OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("Private review draft") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = enabled && notes.isNotBlank() && board.canRun("review_draft"), onClick = {
            model.workspaceCommand(
                binding,
                "review_draft",
                buildJsonObject {
                    put("draft_id", draftId)
                    put("body", notes)
                    put("check_id", checkId)
                }
            )
        }) { Text("Save review draft") }
        Text("Review drafts stay with this job and source. They are not posted to GitHub.", style = MaterialTheme.typography.bodySmall)
    }
    board.reviewDrafts.forEach { draft ->
        GatewayWorkspaceItem("Saved draft · ${draft.workspaceText("check_id")?.take(12) ?: board.headSha.take(12)}") {
            draft.workspaceText("body")?.let { SelectionContainer { Text(it) } }
            draft.workspaceText("path")?.let { Text("$it:${draft.workspaceText("line").orEmpty()}", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (board.canRun("candidate_rank")) {
        TextButton(onClick = {
            model.workspaceCommand(
                binding,
                "candidate_rank",
                buildJsonObject {
                    put("check_ids", JsonArray(listOf(requireNotNull(baseline), requireNotNull(selected)).map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
            )
        }, enabled = enabled && baseline != null && selected != null && baseline != selected && history?.candidates?.any { it.checkId == baseline && it.sourceSha == board.headSha } == true && history.candidates.any { it.checkId == selected && it.sourceSha == board.headSha }) { Text("Compare qualification and cost") }
    }
    if (board.candidateComparison.isNotEmpty()) GatewayWorkspaceReceipt("Candidate comparison evidence", board.candidateComparison.toString())
}
