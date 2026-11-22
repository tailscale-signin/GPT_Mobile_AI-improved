package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.chungjungsoo.gptmobile.data.github.GatewayAgentWorkspace
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubCodeDiff

@Composable
internal fun GatewayWorkspaceSourceBrowser(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, state: GatewayEngineeringUiState, model: GatewayEngineeringViewModel) {
    var query by remember(board.jobId, board.headSha) { mutableStateOf("") }
    val tree = state.workspaceTree?.takeIf { it.jobId == board.jobId && it.headSha == board.headSha }
    val source = state.workspaceSource?.takeIf { it.jobId == board.jobId && it.headSha == board.headSha }
    Text("Pinned source files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gateway_workspace_source"))
    Text("Browse the exact Git source used by this job. Candidate edits and recorded checks appear below.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(query, { query = it.take(200) }, label = { Text("Filter repository paths") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row {
        TextButton(onClick = { model.workspaceTree(binding, query.trim()) }, enabled = !state.busy) { Text("Browse source") }
        tree?.nextOffset?.let { next ->
            TextButton(onClick = { model.workspaceTree(binding, state.workspaceTreeQuery, next) }, enabled = !state.busy) { Text("Next file page") }
        }
    }
    if (tree != null) {
        Text("${tree.paths.size} files shown · ${tree.total} matching paths · source ${tree.headSha.take(12)}", style = MaterialTheme.typography.labelSmall)
        tree.paths.forEach { file ->
            TextButton(onClick = { model.workspaceSource(binding, file) }, enabled = !state.busy) { Text(file.path) }
        }
        if (tree.paths.isEmpty()) Text("No files match this path filter.")
    }
    if (source != null) {
        GatewayWorkspaceItem(source.path) {
            Text("Blob ${source.blobSha.take(12)} · lines ${source.startLine}–${source.endLine} of ${source.totalLines}", style = MaterialTheme.typography.labelSmall)
            val file = tree?.paths?.singleOrNull { it.path == source.path && it.blobSha == source.blobSha }
            Row {
                TextButton(enabled = !state.busy && file != null && source.startLine > 1, onClick = { file?.let { model.workspaceSource(binding, it, (source.startLine - 80).coerceAtLeast(1)) } }) { Text("Previous lines") }
                TextButton(enabled = !state.busy && file != null && source.nextLine != null, onClick = { file?.let { observed -> source.nextLine?.let { model.workspaceSource(binding, observed, it) } } }) { Text("Next lines") }
            }
            GitHubCodeDiff(source.content)
        }
    }
}
