package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class GitHubFileProposal(val path: String, val before: String, val after: String, val delete: Boolean = false) {
    val changed: Boolean get() = delete || before != after
}

@Serializable
data class GitHubReview(val connectionUid: String, val owner: String, val repo: String, val branch: String, val head: String, val checks: JsonObject = JsonObject(emptyMap()), val files: List<GitHubFileProposal> = emptyList(), val pullNumber: Int? = null, val changedFiles: JsonObject = JsonObject(emptyMap()), val reviewNotes: String = "", val inspectedAt: Long = System.currentTimeMillis(), val stale: Boolean = false)

@HiltViewModel
class GitHubReviewViewModel @Inject constructor(private val connections: ToolConnectionRepository, private val vault: SecretVault, private val workspace: WorkspaceRepository, private val engineering: dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringController, private val gitHubPublicAuth: dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator? = null) : ViewModel() {
    val accounts = MutableStateFlow<List<ToolConnection>>(emptyList())
    val record = MutableStateFlow<WorkspaceRecord?>(null)
    val review = MutableStateFlow<GitHubReview?>(null)
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    internal val engineeringTasks = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringTaskRow>>(emptyList())
    internal val engineeringEvents = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringEventRow>>(emptyList())
    internal val selectedEngineeringRun = MutableStateFlow<dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringRunRow?>(null)
    private val dismissedCommitTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val pendingEngineeringTask = MutableStateFlow<String?>(null)
    internal val programmerScope = MutableStateFlow<dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext?>(null)
    fun restrictTo(scope: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext?) {
        if (programmerScope.value == scope) return
        programmerScope.value = scope
        engineeringTasks.value = emptyList()
        engineeringEvents.value = emptyList()
        selectedEngineeringRun.value = null
        pendingEngineeringTask.value = null
        review.value = null
        record.value = null
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { refreshEngineeringTasksNow() }
    }
    private suspend fun refreshEngineeringTasksNow() {
        val scope = programmerScope.value
        val tasks = engineering.recent().filter { task -> scope == null || (task.repositoryOwner.equals(scope.owner, true) && task.repositoryName.equals(scope.repo, true) && engineering.run(task.taskId)?.connectionUid == scope.connectionUid) }
        if (programmerScope.value == scope) engineeringTasks.value = tasks
    }
    fun refreshEngineeringTasks() = action { refreshEngineeringTasksNow() }
    init {
        action {
            refreshEngineeringTasksNow()
            accounts.value = connections.listConnections().filter { it.type == "GITHUB" }
            workspace.dao.list("github").firstOrNull()?.let {
                record.value = it
                val saved: GitHubReview = workspace.json.decodeFromString(it.payload)
                val scope = programmerScope.value
                if (scope == null || (saved.connectionUid == scope.connectionUid && saved.owner.equals(scope.owner, true) && saved.repo.equals(scope.repo, true))) review.value = saved
            }
        }
    }
    private suspend fun client(uid: String): GitHubWorkspaceClient {
        val connection = requireNotNull(connections.getConnection(uid)) { "Select a GitHub connection." }
        gitHubPublicAuth?.let { return GitHubWorkspaceClient(it.accessToken(connection), freshnessSeconds = 0) }
        val bytes = connection.secretRef?.let { vault.read(it) }
        val token = try {
            bytes?.decodeToString().orEmpty()
        } finally {
            bytes?.fill(0)
        }
        return GitHubWorkspaceClient(token, freshnessSeconds = 0)
    }
    private fun arguments(review: GitHubReview, extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("owner", review.owner)
        put("repo", review.repo)
        put("branch", review.branch)
        put("ref", review.head)
        extra.forEach { (key, value) -> put(key, value) }
    }
    private suspend fun save(value: GitHubReview) {
        val entry = WorkspaceRecord(record.value?.id ?: UUID.randomUUID().toString(), "github", "${value.owner}/${value.repo} · ${value.branch}", workspace.json.encodeToString(value))
        workspace.dao.save(entry)
        record.value = entry
        review.value = value
    }
    fun inspect(uid: String, owner: String, repo: String, branch: String, pr: String) = action {
        require(review.value?.files?.none { it.changed } != false) { "Discard or commit the current proposal before changing destination." }
        val client = client(uid)
        val base = GitHubReview(uid, owner.trim(), repo.trim(), branch.trim(), "")
        val head = client.execute("get_branch_head", arguments(base)).jsonObject["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content ?: error("Branch head unavailable.")
        val value = base.copy(head = head, pullNumber = pr.toIntOrNull()?.also { require(it > 0) })
        val checks = client.execute("get_commit_checks", arguments(value)).jsonObject
        val files = value.pullNumber?.let { client.execute("get_pull_request_files", arguments(value, buildJsonObject { put("pull_number", it) })).jsonObject } ?: JsonObject(emptyMap())
        record.value = null
        save(value.copy(checks = checks, changedFiles = files))
        notice.value = "Checks are attached to $head. PR files are a live catalog; refresh if the PR changes."
    }
    fun refresh() = action {
        val old = requireNotNull(review.value)
        val client = client(old.connectionUid)
        val head = client.execute("get_branch_head", arguments(old)).jsonObject["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
        if (head != old.head) {
            save(old.copy(stale = true))
            notice.value = "Branch moved. The proposal and checks remain attached to ${old.head}. Discard the proposal and inspect again to reconcile."
        } else {
            save(old.copy(checks = client.execute("get_commit_checks", arguments(old)).jsonObject, inspectedAt = System.currentTimeMillis()))
            notice.value = client.execute("rate_limit_status", JsonObject(emptyMap())).toString()
        }
    }
    fun openFile(path: String) = action {
        val old = requireNotNull(review.value)
        require(!old.stale && old.files.size < 20)
        require(old.files.none { it.path == path }) { "This file is already open. Its local edits have been preserved." }
        GitHubWorkspaceClient.filePath(path)
        val file = client(old.connectionUid).execute(
            "read_code",
            arguments(
                old,
                buildJsonObject {
                    put("path", path)
                    put("start_line", 1)
                    put("end_line", 2000)
                }
            )
        ).jsonObject
        require(file["has_more"]?.jsonPrimitive?.booleanOrNull != true) { "This file exceeds the 2,000-line editor limit. A partial file cannot be committed." }
        val text = file.getValue("content").jsonPrimitive.content
        save(old.copy(files = old.files.filterNot { it.path == path } + GitHubFileProposal(path, text, text)))
    }
    fun edit(path: String, value: String) {
        review.value = review.value?.let { it.copy(files = it.files.map { file -> if (file.path == path) file.copy(after = value, delete = false) else file }) }
    }
    fun deleteFile(path: String) {
        review.value = review.value?.let { proposal ->
            proposal.copy(
                files = proposal.files.map { file ->
                    if (file.path == path) file.copy(after = if (file.delete) file.before else "", delete = !file.delete) else file
                }
            )
        }
    }
    fun saveDraft() = action {
        save(requireNotNull(review.value))
        notice.value = "Proposal saved locally."
    }
    fun notes(value: String) {
        review.value = review.value?.copy(reviewNotes = value.take(8000))
    }
    fun discard() = action {
        val old = requireNotNull(review.value)
        save(old.copy(files = emptyList()))
        notice.value = "Local proposal discarded. Inspect the destination again to refresh a moved branch."
    }
    fun prepareCommit(message: String, requiresHardware: Boolean = false) = action {
        val proposal = requireNotNull(review.value)
        require(!proposal.stale) { "Refresh the moved branch before committing." }
        val files = proposal.files.filter { it.changed }.map {
            dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringFilePatch(it.path, it.before, it.after, it.delete)
        }
        val taskId = engineering.prepare(proposal.connectionUid, proposal.owner, proposal.repo, proposal.branch, proposal.head, files, message, requiresHardware)
        pendingEngineeringTask.value = taskId
        selectedEngineeringRun.value = engineering.run(taskId)
        engineeringEvents.value = engineering.events(taskId)
        refreshEngineeringTasksNow()
        notice.value = "Patch validated for paths, size and credentials. Review and approve this exact snapshot. Compilation and hardware remain unverified."
    }
    fun approveCommit(taskId: String, digest: String, onCommitted: (String) -> Unit = {}) = action {
        val task = engineeringTasks.value.single { it.taskId == taskId }
        val frozen = requireNotNull(engineering.run(taskId))
        val visible = requireNotNull(review.value)
        require(visible.owner == task.repositoryOwner && visible.repo == task.repositoryName && visible.branch == task.workingBranch && visible.head == task.baseSha)
        require(
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringAuthorization.patchJson(
                visible.files.filter { it.changed }.map {
                    dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringFilePatch(it.path, it.before, it.after, it.delete)
                }
            ) == frozen.patchJson
        ) { "The visible patch changed; validate and review it again." }
        engineering.approve(taskId, digest)
        val sha = engineering.execute(taskId)
        review.value?.let { proposal ->
            save(proposal.copy(head = sha, files = emptyList(), checks = JsonObject(emptyMap()), reviewNotes = "Commit $sha published; repository-required CI is being monitored."))
        }
        pendingEngineeringTask.value = null
        selectedEngineeringRun.value = engineering.run(taskId)
        engineeringEvents.value = engineering.events(taskId)
        refreshEngineeringTasksNow()
        notice.value = "Remote commit verified. CI evidence is pending."
        onCommitted(sha)
    }
    fun inspectEngineeringTask(taskId: String) = action {
        selectedEngineeringRun.value = engineering.run(taskId)
        engineeringEvents.value = engineering.events(taskId)
        refreshEngineeringTasksNow()
    }
    fun resumeEngineering(taskId: String) = action {
        engineering.recover()
        inspectEngineeringTaskNow(taskId)
    }
    private suspend fun inspectEngineeringTaskNow(taskId: String) {
        selectedEngineeringRun.value = engineering.run(taskId)
        engineeringEvents.value = engineering.events(taskId)
        refreshEngineeringTasksNow()
    }
    fun verifyEngineeringHardware(taskId: String) = action {
        engineering.attachHardwareVerification(taskId)
        inspectEngineeringTaskNow(taskId)
    }
    fun revokeEngineering(taskId: String) = action {
        engineering.revoke(taskId)
        inspectEngineeringTaskNow(taskId)
    }
    fun cancelEngineering(taskId: String) = action {
        engineering.cancel(taskId)
        inspectEngineeringTaskNow(taskId)
    }
    fun reviewEngineering(taskId: String) = action { reviewEngineeringNow(taskId) }
    private suspend fun reviewEngineeringNow(taskId: String) {
        val scope = programmerScope.value
        val task = engineering.recent().single { it.taskId == taskId }
        val run = requireNotNull(engineering.run(taskId))
        require(scope == null || (run.connectionUid == scope.connectionUid && task.repositoryOwner.equals(scope.owner, true) && task.repositoryName.equals(scope.repo, true))) { "This proposal belongs to another workspace." }
        require(scope != null || review.value?.files?.none { it.changed } != false || selectedEngineeringRun.value?.taskId == taskId) { "Save or discard the current proposal before reviewing a saved task." }
        val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringAuthorization.files(run.patchJson).map { GitHubFileProposal(it.path, it.before, it.after, it.delete) }
        }
        if (programmerScope.value != scope) return
        record.value = null
        save(GitHubReview(run.connectionUid, task.repositoryOwner, task.repositoryName, task.workingBranch, task.baseSha, files = files))
        inspectEngineeringTaskNow(taskId)
        pendingEngineeringTask.value = null
        notice.value = "Review the proposed files below, then choose Approve reviewed patch. No commit has been sent."
    }
    suspend fun refreshWorkspaceApproval(scope: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext) {
        if (busy.value || programmerScope.value != scope || pendingEngineeringTask.value != null) return
        busy.value = true
        try {
            refreshEngineeringTasksNow()
            val task = engineeringTasks.value.firstOrNull { it.state == "AWAITING_APPROVAL" && it.taskId !in dismissedCommitTasks } ?: return
            // Hydrate the frozen patch before presenting the one commit confirmation.
            reviewEngineeringNow(task.taskId)
            if (programmerScope.value == scope) pendingEngineeringTask.value = task.taskId
        } finally {
            busy.value = false
        }
    }
    fun requestEngineeringApproval(taskId: String) = action {
        require(selectedEngineeringRun.value?.taskId == taskId && review.value != null) { "Open and review the saved patch first." }
        pendingEngineeringTask.value = taskId
    }
    fun dismissApproval() {
        pendingEngineeringTask.value?.let { dismissedCommitTasks += it }
        pendingEngineeringTask.value = null
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice.value = error.message.orEmpty()
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun GitHubReviewPanel(model: GitHubReviewViewModel = hiltViewModel(), programmerContext: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext? = null) {
    val activeScope by model.programmerScope.collectAsState()
    LaunchedEffect(programmerContext) { model.restrictTo(programmerContext) }
    val accounts by model.accounts.collectAsState()
    val review by model.review.collectAsState()
    val notice by model.notice.collectAsState()
    val busy by model.busy.collectAsState()
    val tasks by model.engineeringTasks.collectAsState()
    val events by model.engineeringEvents.collectAsState()
    val selectedRun by model.selectedEngineeringRun.collectAsState()
    var uid by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var pr by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var requiresHardware by remember { mutableStateOf(false) }
    if (programmerContext != null && activeScope != programmerContext) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            WorkspaceCard("GitHub review") {
                if (programmerContext == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) { accounts.forEach { FilterChip(uid == it.connectionUid, { uid = it.connectionUid }, label = { Text(it.name) }) } }
                    OutlinedTextField(owner, { owner = it }, label = { Text("Owner") })
                    OutlinedTextField(repo, { repo = it }, label = { Text("Repository") })
                    OutlinedTextField(branch, { branch = it }, label = { Text("Working branch") })
                    OutlinedTextField(pr, { pr = it }, label = { Text("Pull request number · optional") })
                    Button(enabled = !busy, onClick = { model.inspect(uid, owner, repo, branch, pr) }) { Text("Inspect branch") }
                } else {
                    Text("${programmerContext.owner}/${programmerContext.repo}", style = MaterialTheme.typography.titleMedium)
                    Text("Review AI-proposed changes and verified operation states.")
                    OutlinedButton(onClick = model::refreshEngineeringTasks, enabled = !busy) { Text("Refresh proposed changes") }
                    if (tasks.isEmpty()) Text("No engineering proposals yet. Ask your main AI profile to prepare a change.")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(notice)
            }
        }
        if (tasks.isNotEmpty()) {
            item {
                WorkspaceCard("Engineering tasks") {
                    tasks.forEach { task ->
                        OutlinedButton(enabled = !busy, onClick = { model.inspectEngineeringTask(task.taskId) }) {
                            Text("${task.repositoryOwner}/${task.repositoryName} · ${task.workingBranch} · ${task.state}")
                        }
                    }
                    selectedRun?.let { run ->
                        Text("Task ${run.taskId} · patch ${run.patchDigest.take(12)}")
                        Text(run.notice)
                        Text("Structural validation: recorded · CI: ${if (run.verifiedChecksJson == null) "unverified" else "receipts recorded; see task state"} · Emulator: no receipt · Physical device: ${if (run.hardwareReceiptJson == null) "unverified" else "receipt recorded"}")
                        events.filter { it.taskId == run.taskId }.forEach { Text("${it.sequence}. ${it.resultingState} · ${it.evidenceDigest.take(12)}", style = MaterialTheme.typography.bodySmall) }
                        SelectionContainer { Text(run.verifiedChecksJson.orEmpty().take(12000)) }
                        OutlinedButton(enabled = !busy, onClick = { model.resumeEngineering(run.taskId) }) { Text("Reconcile & refresh") }
                        if (tasks.firstOrNull { it.taskId == run.taskId }?.state in setOf("AWAITING_APPROVAL", "COMMITTING")) {
                            OutlinedButton(enabled = !busy, onClick = { model.reviewEngineering(run.taskId) }) { Text("View proposed changes") }
                            if (review != null) OutlinedButton(enabled = !busy, onClick = { model.requestEngineeringApproval(run.taskId) }) { Text("Approve reviewed patch") }
                        }
                        if (tasks.firstOrNull { it.taskId == run.taskId }?.state == "AWAITING_HARDWARE_VERIFICATION") {
                            OutlinedButton(enabled = !busy, onClick = { model.verifyEngineeringHardware(run.taskId) }) { Text("Verify this device's benchmark receipts") }
                        }
                        OutlinedButton(enabled = !busy, onClick = { model.revokeEngineering(run.taskId) }) { Text("Revoke approval") }
                        OutlinedButton(enabled = !busy, onClick = { model.cancelEngineering(run.taskId) }) { Text("Cancel task") }
                    }
                }
            }
        }
        review?.let { value ->
            item {
                WorkspaceCard("${value.owner}/${value.repo} → ${value.branch}") {
                    Text("Inspected SHA: ${value.head}")
                    if (value.stale) Text("STALE · branch changed", color = MaterialTheme.colorScheme.error)
                    Text("Checks for ${value.head.take(12)}", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(value.checks.toString()) }
                    OutlinedButton(enabled = !busy, onClick = { model.refresh() }) { Text("Refresh head, checks & rate limit") }
                    if (value.changedFiles.isNotEmpty()) SelectionContainer { Text(value.changedFiles.toString().take(16000)) }
                    if (programmerContext == null) {
                        OutlinedTextField(path, { path = it }, label = { Text("Repository file path") })
                        OutlinedButton(enabled = !busy && !value.stale, onClick = { model.openFile(path) }) { Text("Open at inspected SHA") }
                        OutlinedTextField(value.reviewNotes, { model.notes(it) }, label = { Text("Review & test evidence for this SHA") }, maxLines = 4)
                    }
                }
            }
            items(value.files, key = { it.path }) { file ->
                WorkspaceCard(file.path) {
                    Text(if (file.delete) "Delete file" else "Replacement diff", style = MaterialTheme.typography.labelLarge)
                    val diff by androidx.compose.runtime.produceState("Preparing diff…", file.path, file.before, file.after, file.delete) {
                        kotlinx.coroutines.delay(120)
                        this.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            dev.chungjungsoo.gptmobile.data.workspace.ReviewDiff.render(file.path, file.before, file.after).take(12000)
                        }
                    }
                    SelectionContainer { Text(diff, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    if (file.before.length + file.after.length > 12000) Text("Diff preview may be truncated. Review the complete proposed file below before committing.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(file.after, { model.edit(file.path, it) }, readOnly = programmerContext != null, enabled = !file.delete, label = { Text("Proposed complete file") }, modifier = Modifier.fillMaxWidth(), maxLines = 16)
                    if (programmerContext == null) OutlinedButton(enabled = !busy && !value.stale, onClick = { model.deleteFile(file.path) }) { Text(if (file.delete) "Restore file" else "Delete file in this commit") }
                    Text(
                        "${file.before.lines().size} → ${file.after.lines().size} lines · ${if (file.delete) {
                            "deleted"
                        } else if (file.before == file.after) {
                            "unchanged"
                        } else {
                            "modified"
                        }}"
                    )
                }
            }
            item {
                OutlinedTextField(message, { message = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
                FilterChip(requiresHardware, { requiresHardware = !requiresHardware }, label = { Text("Require physical-device verification") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { model.saveDraft() }) { Text("Save proposal") }
                    OutlinedButton(enabled = !busy, onClick = { model.discard() }) { Text("Discard") }
                    OutlinedButton(enabled = !busy && !value.stale && value.files.any { it.changed } && message.isNotBlank(), onClick = { model.prepareCommit(message, requiresHardware) }) { Text("Commit changes") }
                }
            }
        }
    }
    if (programmerContext == null) GitHubCommitApprovalDialog(model)
}

@Composable
internal fun GitHubCommitApprovalDialog(model: GitHubReviewViewModel, onCommitted: (String) -> Unit = {}) {
    val pendingTask by model.pendingEngineeringTask.collectAsState()
    val selectedRun by model.selectedEngineeringRun.collectAsState()
    val tasks by model.engineeringTasks.collectAsState()
    val review by model.review.collectAsState()
    val busy by model.busy.collectAsState()
    if (pendingTask != null && selectedRun?.taskId == pendingTask) {
        val run = requireNotNull(selectedRun)
        val task = tasks.firstOrNull { it.taskId == run.taskId }
        AlertDialog(onDismissRequest = { model.dismissApproval() }, title = { Text("Approve commit?") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${task?.repositoryOwner}/${task?.repositoryName}")
                Text("Branch: ${task?.workingBranch}")
                Text(run.commitMessage.lineSequence().firstOrNull().orEmpty())
                review?.files?.filter { it.changed }?.take(8)?.forEach { file ->
                    Text(
                        "• ${if (file.delete) {
                            "Delete"
                        } else if (file.before.isEmpty()) {
                            "Add"
                        } else {
                            "Update"
                        }} ${file.path}"
                    )
                }
                val remaining = (review?.files?.count { it.changed } ?: 0) - 8
                if (remaining > 0) Text("• $remaining more files")
            }
        }, confirmButton = {
            OutlinedButton(enabled = !busy, onClick = { model.approveCommit(run.taskId, run.patchDigest, onCommitted) }) { Text("Approve & continue") }
        }, dismissButton = { OutlinedButton(onClick = { model.dismissApproval() }) { Text("Keep for review") } })
    }
}
