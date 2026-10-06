package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class GitHubFileProposal(val path: String, val before: String, val after: String)

@Serializable
data class GitHubReview(val connectionUid: String, val owner: String, val repo: String, val branch: String, val head: String, val checks: JsonObject = JsonObject(emptyMap()), val files: List<GitHubFileProposal> = emptyList(), val pullNumber: Int? = null, val changedFiles: JsonObject = JsonObject(emptyMap()), val reviewNotes: String = "", val inspectedAt: Long = System.currentTimeMillis(), val stale: Boolean = false)

@HiltViewModel
class GitHubReviewViewModel @Inject constructor(private val connections: ToolConnectionRepository, private val vault: SecretVault, private val workspace: WorkspaceRepository) : ViewModel() {
    val accounts = MutableStateFlow<List<ToolConnection>>(emptyList())
    val record = MutableStateFlow<WorkspaceRecord?>(null)
    val review = MutableStateFlow<GitHubReview?>(null)
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    init {
        action {
            accounts.value = connections.listConnections().filter { it.type == "GITHUB" }
            workspace.dao.list("github").firstOrNull()?.let {
                record.value = it
                review.value = workspace.json.decodeFromString(it.payload)
            }
        }
    }
    private suspend fun client(uid: String): GitHubWorkspaceClient {
        val connection = requireNotNull(connections.getConnection(uid)) { "Select a GitHub connection." }
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
        require(review.value?.files?.none { it.before != it.after } != false) { "Discard or commit the current proposal before changing destination." }
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
        review.value = review.value?.let { it.copy(files = it.files.map { file -> if (file.path == path) file.copy(after = value) else file }) }
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
    fun commit(message: String) = action {
        val proposal = requireNotNull(review.value)
        require(!proposal.stale) { "Refresh the moved branch before committing." }
        val files = proposal.files.filter { it.before != it.after }
        require(files.isNotEmpty()) { "There are no edits to submit." }
        val result = client(proposal.connectionUid).execute(
            "commit_files",
            arguments(
                proposal,
                buildJsonObject {
                    put("expected_head_sha", proposal.head)
                    put("message", message)
                    put(
                        "files",
                        buildJsonArray {
                            files.forEach {
                                add(
                                    buildJsonObject {
                                        put("path", it.path)
                                        put("content", it.after)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        ).jsonObject
        save(proposal.copy(head = result.getValue("sha").jsonPrimitive.content, files = emptyList(), checks = JsonObject(emptyMap()), reviewNotes = "Previous review applied to ${proposal.head}. Re-run checks for this commit."))
        notice.value = "Commit created. Check results for the previous SHA are cleared."
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
fun GitHubReviewPanel(model: GitHubReviewViewModel = hiltViewModel()) {
    val accounts by model.accounts.collectAsState()
    val review by model.review.collectAsState()
    val notice by model.notice.collectAsState()
    val busy by model.busy.collectAsState()
    var uid by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var pr by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            WorkspaceCard("GitHub review") {
                Row(Modifier.horizontalScroll(rememberScrollState())) { accounts.forEach { FilterChip(uid == it.connectionUid, { uid = it.connectionUid }, label = { Text(it.name) }) } }
                OutlinedTextField(owner, { owner = it }, label = { Text("Owner") })
                OutlinedTextField(repo, { repo = it }, label = { Text("Repository") })
                OutlinedTextField(branch, { branch = it }, label = { Text("Working branch") })
                OutlinedTextField(pr, { pr = it }, label = { Text("Pull request number · optional") })
                Button(enabled = !busy, onClick = { model.inspect(uid, owner, repo, branch, pr) }) { Text("Inspect branch") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(notice)
            }
        }
        review?.let { value ->
            item {
                WorkspaceCard("${value.owner}/${value.repo} → ${value.branch}") {
                    Text("Inspected SHA: ${value.head}")
                    if (value.stale) Text("STALE · branch changed", color = MaterialTheme.colorScheme.error)
                    Text("Checks for ${value.head.take(12)}", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(value.checks.toString()) }
                    TextButton(enabled = !busy, onClick = { model.refresh() }) { Text("Refresh head, checks & rate limit") }
                    if (value.changedFiles.isNotEmpty()) SelectionContainer { Text(value.changedFiles.toString().take(16000)) }
                    OutlinedTextField(path, { path = it }, label = { Text("Repository file path") })
                    TextButton(enabled = !busy && !value.stale, onClick = { model.openFile(path) }) { Text("Open at inspected SHA") }
                    OutlinedTextField(value.reviewNotes, { model.notes(it) }, label = { Text("Review & test evidence for this SHA") }, maxLines = 4)
                }
            }
            items(value.files, key = { it.path }) { file ->
                WorkspaceCard(file.path) {
                    Text("Replacement diff", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(dev.chungjungsoo.gptmobile.data.workspace.ReviewDiff.render(file.path, file.before, file.after).take(12000), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    if (file.before.length + file.after.length > 12000) Text("Diff preview may be truncated. Review the complete proposed file below before committing.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(file.after, { model.edit(file.path, it) }, label = { Text("Proposed complete file") }, modifier = Modifier.fillMaxWidth(), maxLines = 16)
                    Text("${file.before.lines().size} → ${file.after.lines().size} lines · ${if (file.before == file.after) "unchanged" else "modified"}")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { model.saveDraft() }) { Text("Save proposal") }
                    TextButton(enabled = !busy, onClick = { model.discard() }) { Text("Discard") }
                    TextButton(enabled = !busy && !value.stale && value.files.any { it.before != it.after }, onClick = { confirming = true }) { Text("Review commit") }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Commit reviewed changes?") }, text = {
            Column {
                Text("${review?.owner}/${review?.repo} → ${review?.branch}\nExpected head: ${review?.head}\n${review?.files?.filter { it.before != it.after }?.joinToString("\n") { it.path }}")
                Text("The branch must still match the inspected SHA. This creates a commit on the working branch; merging remains separate.")
                OutlinedTextField(message, { message = it }, label = { Text("Commit message") })
            }
        }, confirmButton = {
            TextButton(onClick = {
                confirming = false
                model.commit(message)
            }) { Text("Commit these files") }
        }, dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
    }
}
