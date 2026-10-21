package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The same theme-aware repository session surface is used by the actual Workspace and render tests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GitHubRepositoryInsights(
    state: GitHubWorkspaceState,
    onSearch: (String, String) -> Unit,
    onContinueSearch: () -> Unit,
    onExplainCi: () -> Unit,
    onMarkReviewed: () -> Unit,
    onChanges: (Boolean) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(state.selection?.fullName) { mutableStateOf(false) }
    var searchVisible by rememberSaveable(state.selection?.fullName) { mutableStateOf(false) }
    var query by rememberSaveable(state.selection?.fullName) { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("literal") }
    val colors = MaterialTheme.colorScheme
    val index = state.indexStatus?.takeIf { it.text("commit_sha") == state.headSha }
    val indexed = index?.text("indexed_blobs")?.toIntOrNull() ?: 0
    val total = index?.text("total_paths")?.toIntOrNull() ?: 0
    Surface(shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth().testTag("github_repository_session"), color = colors.surfaceContainerLow) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.42f), colors.surfaceContainerLow, colors.tertiaryContainer.copy(alpha = 0.18f)))).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.AccountTree, null, tint = colors.primary)
                Column(Modifier.weight(1f)) {
                    Text("Repository session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(state.selection?.fullName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text(if (state.canWrite) "Can edit" else "Read only", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SessionPill("@${state.login.ifBlank { "connected account" }}")
                SessionPill(state.selection?.ref.orEmpty())
                SessionPill(state.headSha.take(10).ifBlank { "Resolving revision" })
            }
            state.observedAt?.let { timestamp ->
                Text("Snapshot checked ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp))}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (total > 0) {
                LinearProgressIndicator(progress = { (indexed.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), trackColor = colors.surfaceContainerHighest)
                Text("$indexed of $total source blobs indexed · ${if (index?.flag("complete") == true) "complete" else "partial coverage"}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { searchVisible = !searchVisible }, enabled = !state.busy) { Text("Search this revision") }
                OutlinedButton(onClick = onExplainCi, enabled = !state.busy) { Text("Explain failed CI") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Session details") }
            }
            if (searchVisible) {
                OutlinedTextField(query, { query = it.take(200) }, label = { Text("Find in this commit") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                    IconButton(onClick = { onSearch(query, mode) }, enabled = query.isNotBlank() && !state.busy) { Icon(Icons.Rounded.Search, "Search repository revision") }
                })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("literal" to "Text", "paths" to "Paths", "definitions" to "Symbols", "references" to "References", "tests" to "Tests").forEach { (value, label) ->
                        FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) }, enabled = !state.busy)
                    }
                }
            }
            state.searchResult?.let { result ->
                Text("Search: ${result.text("query")}", style = MaterialTheme.typography.titleSmall)
                Text("${result.text("scanned_files")} files scanned in this page · ${result.text("new_blobs")} new downloads", style = MaterialTheme.typography.labelSmall)
                if (!result.flag("complete")) Text("Coverage is partial. Continue indexing to search the remaining files. Symbol matches are lexical; compiler resolution is unavailable.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                result["items"]?.jsonArray.orEmpty().forEach { row ->
                    val item = row.jsonObject
                    key(item.text("path")) {
                        Surface(onClick = { onOpenFile(item.text("path")) }, enabled = !state.busy, shape = RoundedCornerShape(12.dp), color = colors.surface.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.text("path"), style = MaterialTheme.typography.labelLarge)
                                item["matches"]?.jsonArray?.firstOrNull()?.jsonObject?.let { hit -> Text("${hit.text("line")}: ${hit.text("text")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3) }
                            }
                        }
                    }
                }
                if (result.flag("has_more")) TextButton(onClick = onContinueSearch, enabled = !state.busy) { Text("Continue indexing and search") }
            }
            if (expanded) {
                HorizontalDivider()
                Text("Capabilities & efficiency", style = MaterialTheme.typography.titleSmall)
                Text(if (state.canWrite) "Revision reads, search, review and expected-head commits are available." else "Revision reads, search and review are available. Repository writes require account access.", style = MaterialTheme.typography.bodySmall)
                val requests = state.efficiency?.get("requests") as? JsonObject
                if (requests != null) {
                    Text("${requests.text("physical_requests")} API requests · ${requests.text("cache_hits")} cache reuses · ${requests.text("singleflight_joins")} shared reads", style = MaterialTheme.typography.bodySmall)
                    Text("${requests.text("downloaded_bytes").toLongOrNull()?.div(1024) ?: 0} KiB transferred · ${requests.text("active_requests")} active / ${requests.text("queued_requests")} queued", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Text("Network reuse is measured separately from model tokens. Source excerpts stay tied to the commit above.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMarkReviewed, enabled = !state.busy && state.headSha.isNotBlank()) { Text("Mark revision reviewed") }
                    TextButton(onClick = { onChanges(false) }, enabled = !state.busy && state.reviewBaseline.isNotBlank()) { Text("Changes since review") }
                }
                if (state.reviewBaseline.isNotBlank()) Text("Review baseline · ${state.reviewBaseline.take(10)}", style = MaterialTheme.typography.labelSmall)
            }
            state.changedFiles?.let { result ->
                HorizontalDivider()
                Text("${result.text("files_total")} changed files since review", style = MaterialTheme.typography.titleSmall)
                if (!result.flag("tree_complete")) Text("Tree coverage is incomplete.", color = colors.error, style = MaterialTheme.typography.bodySmall)
                result["files"]?.jsonArray.orEmpty().forEach { row ->
                    val file = row.jsonObject
                    Text("${file.text("status")} · ${file.text("filename")}", modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy && file.text("status") != "removed") { onOpenFile(file.text("filename")) }.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (result.flag("has_more")) TextButton(onClick = { onChanges(true) }, enabled = !state.busy) { Text("Next changed files") }
            }
            state.ciDigest?.let { digest ->
                HorizontalDivider()
                Text("CI failure evidence", style = MaterialTheme.typography.titleSmall)
                if (digest.text("status") == "available") {
                    Text("Run ${digest.text("run_id")} · head ${digest.text("head_sha").take(10)}", style = MaterialTheme.typography.labelSmall)
                    Text(if (digest.flag("log_complete")) "The captured log was scanned for failure windows." else "Log coverage is partial; the bounded capture may omit later output.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    val excerpt = digest["excerpts"]?.jsonArray.orEmpty().joinToString("\n") { row -> row.jsonObject.let { "${it.text("line")}: ${it.text("text")}" } }
                    Text(excerpt.take(10000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    if (excerpt.length > 10000) Text("Additional captured windows are available through the CI evidence handle.", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(digest.text("status").replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SessionPill(label: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun GitHubPatchPreview(stage: JsonObject) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Exact patch preview", style = MaterialTheme.typography.titleMedium)
            Text("Expected head · ${stage.text("expected_head_sha").take(10)}", style = MaterialTheme.typography.labelSmall)
            stage["preview"]?.jsonArray.orEmpty().forEach { value ->
                val file = value.jsonObject
                Text(file.text("path"), style = MaterialTheme.typography.labelLarge)
                Text("Before", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Text(file.text("before"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("After", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(file.text("after"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text("Preview excerpts are bounded. Exact anchors and original blob hashes are checked before the full files are reconstructed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
