package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.common.AppGlassSurface
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog

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
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val proposal = review ?: return
    val files = remember(proposal.files) { proposal.files.filter { it.changed } }
    var showDetails by remember(snapshot.taskId, snapshot.patchDigest) { mutableStateOf(false) }
    var editPath by remember(snapshot.taskId, snapshot.patchDigest) { mutableStateOf<String?>(null) }
    var search by remember(snapshot.taskId, snapshot.patchDigest, editPath) { mutableStateOf("") }
    var replacement by remember(snapshot.taskId, snapshot.patchDigest, editPath) { mutableStateOf("") }
    val canCommit = !busy && !proposal.stale && files.isNotEmpty()
    val status = if (proposal.stale) "Branch changed. Refresh the proposal before committing." else notice
    GitHubApprovalSummary(
        summary = compactApprovalSummary(files.size, proposal.branch, snapshot.commitMessage, "${proposal.owner}/${proposal.repo}"),
        busy = busy,
        canCommit = canCommit,
        notice = status,
        onCommit = { model.approveCommit(snapshot.taskId, snapshot.patchDigest, onCommitted) },
        onDetails = { showDetails = true },
        onReject = { model.rejectCommit(snapshot.taskId) }
    )
    if (showDetails) {
        FadingDialog(onDismissRequest = { showDetails = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BoxWithConstraints(Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(12.dp)) {
                AppGlassSurface(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("github_approval_details")) {
                    val detailScroll = rememberLazyListState()
                    LaunchedEffect(editPath) { if (editPath != null) detailScroll.scrollToItem(1) }
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Proposed changes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { showDetails = false }) { Text("Close") }
                        }
                        LazyColumn(Modifier.weight(1f, fill = false), state = detailScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                SelectionContainer {
                                    Text("${proposal.owner}/${proposal.repo} · ${proposal.branch}\nBase ${proposal.head.take(12)}\n${snapshot.commitMessage}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (editPath != null) {
                                item {
                                    Text("Edit proposed text in $editPath", style = MaterialTheme.typography.labelLarge)
                                    OutlinedTextField(search, { search = it }, enabled = !busy, label = { Text("Exact text to replace") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(replacement, { replacement = it }, enabled = !busy, label = { Text("Replacement") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                                    Button(enabled = !busy && search.isNotBlank() && search != replacement, onClick = {
                                        model.editAndRestage(snapshot.taskId, requireNotNull(editPath), search, replacement)
                                    }) { Text("Validate edited patch") }
                                    TextButton(enabled = !busy, onClick = { editPath = null }) { Text("Cancel edit") }
                                }
                            }
                            items(files, key = { it.path }) { file ->
                                Text(file.path, style = MaterialTheme.typography.labelLarge)
                                val diff = remember(file) { inlinePatchPreview(file.before, file.after, file.delete) }
                                GitHubCodeDiff(diff)
                                if (!file.delete && editPath == null) TextButton(enabled = !busy, onClick = { editPath = file.path }) { Text("Edit inline") }
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Pinned summary has no scroll container; full review and editing live in Details. */
@Composable
internal fun GitHubApprovalSummary(
    summary: String,
    busy: Boolean,
    canCommit: Boolean,
    notice: String,
    onCommit: () -> Unit,
    onDetails: () -> Unit,
    onReject: () -> Unit
) {
    AppGlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("github_chat_approval"), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Review proposed changes", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Keep all actions horizontal and their touch targets intact at large text sizes.
                val iconsOnly = maxWidth < 300.dp * LocalDensity.current.fontScale
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        enabled = canCommit && !busy,
                        onClick = onCommit,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Approve and commit proposed changes" }
                    ) {
                        if (iconsOnly) Icon(Icons.Rounded.Check, null) else Text("Commit", maxLines = 1)
                    }
                    TextButton(
                        onClick = onDetails,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "View proposed change details" }
                    ) {
                        if (iconsOnly) Icon(Icons.Rounded.Description, null) else Text("Details", maxLines = 1)
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = onReject,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Reject proposed changes" }
                    ) {
                        if (iconsOnly) Icon(Icons.Rounded.Close, null) else Text("Reject", maxLines = 1)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun compactApprovalSummary(fileCount: Int, branch: String, commitMessage: String, repository: String = ""): String {
    // Sentence boundaries require whitespace: keep filenames and version numbers intact.
    val firstLine = commitMessage.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    val intent = firstLine.split(Regex("(?<=[.!?])\\s+")).first().take(160).trimEnd('.', '!', '?')
    val target = listOf(repository, branch).filter { it.isNotBlank() }.joinToString(" · ").replace(Regex("\\s+"), " ").take(80)
    return "$fileCount changed ${if (fileCount == 1) "file" else "files"} on $target. ${intent.ifBlank { "Review and commit this proposal" }}."
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
