package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Native approval of the frozen digest; model text can never invoke the buttons. */
@Composable
internal fun GitHubChatApprovalCard(model: GitHubReviewViewModel, onCommitted: (String) -> Unit) {
    val pending by model.pendingEngineeringTask.collectAsStateWithLifecycle()
    val run by model.selectedEngineeringRun.collectAsStateWithLifecycle()
    val review by model.review.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val snapshot = run?.takeIf { it.taskId == pending }
    if (snapshot == null) {
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val proposal = review ?: return
    var editPath by remember(snapshot.taskId) { mutableStateOf<String?>(null) }
    var search by remember(snapshot.taskId, editPath) { mutableStateOf("") }
    var replacement by remember(snapshot.taskId, editPath) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("github_chat_approval")) {
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review proposed changes", style = MaterialTheme.typography.titleMedium)
            Text("${proposal.owner}/${proposal.repo} · ${proposal.branch}")
            Text("Base ${proposal.head.take(12)} · ${snapshot.commitMessage.lineSequence().firstOrNull().orEmpty()}")
            proposal.files.filter { it.changed }.forEach { file ->
                Text(file.path, style = MaterialTheme.typography.labelLarge)
                val diff = remember(file) { inlinePatchPreview(file.before, file.after, file.delete) }
                SelectionContainer { Text(diff, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                if (!file.delete) TextButton(enabled = !busy, onClick = { editPath = file.path }) { Text("Edit Inline") }
            }
            if (editPath != null) {
                Text("Edit proposed text in $editPath")
                OutlinedTextField(search, { search = it }, label = { Text("Exact text to replace") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replacement, { replacement = it }, label = { Text("Replacement") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && search.isNotBlank() && search != replacement, onClick = {
                    model.editAndRestage(snapshot.taskId, requireNotNull(editPath), search, replacement)
                }) { Text("Validate edited patch") }
                TextButton(onClick = { editPath = null }) { Text("Cancel edit") }
            } else {
                Button(enabled = !busy, onClick = { model.approveCommit(snapshot.taskId, snapshot.patchDigest, onCommitted) }) { Text("Approve & Commit") }
                TextButton(enabled = !busy, onClick = { model.rejectCommit(snapshot.taskId) }) { Text("Reject") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun inlinePatchPreview(before: String, after: String, delete: Boolean): String {
    val prefix = before.commonPrefixWith(after).length
    val start = before.lastIndexOf('\n', (prefix - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val old = before.drop(start)
    val new = if (delete) "" else after.drop(start)
    return "@@ changed text @@\n" + old.take(1800).lineSequence().joinToString("\n") { "- $it" } + "\n" +
        new.take(1800).lineSequence().joinToString("\n") { "+ $it" } +
        if (old.length > 1800 || new.length > 1800) "\n[Preview bounded; the complete frozen files are retained in GitHub review.]" else ""
}
