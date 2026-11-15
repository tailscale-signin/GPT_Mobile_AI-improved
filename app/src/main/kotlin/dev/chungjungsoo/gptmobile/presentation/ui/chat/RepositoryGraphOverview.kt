package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One overview is shared by the inline status map and the full-screen explorer. */
@Composable
internal fun RepositoryGraphStatusContent(
    context: GitHubConversationContext,
    state: RepositoryGraphUiState,
    onRefresh: () -> Unit,
    onIndex: () -> Unit,
    onStop: () -> Unit,
    onSelect: (JsonObject) -> Unit,
    onConfigureGateway: (() -> Unit)? = null,
    gatewayConfigured: Boolean = true,
    showTitle: Boolean = true,
    onSearch: (String) -> Unit = {},
    onNextPage: () -> Unit = {},
    onPreviousPage: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onNeighborhood: (JsonObject) -> Unit = {}
) {
    val map = state.visibleMap
    val graph = remember(map) { repositoryGraphPresentation(map) }
    var query by remember(context, state.gatewayProfileUid, state.mapQuery) { mutableStateOf(state.mapQuery) }
    var selectedNode by remember(context, state.gatewayProfileUid, map) { mutableStateOf<JsonObject?>(null) }
    val visible = remember(graph, selectedNode) { graph.neighborhood(selectedNode?.graphText("id")) }
    val metadata = (state.indexStatus?.get("graph") ?: state.result?.get("graph") ?: map?.get("graph")) as? JsonObject
    val processed = (metadata?.get("processed_files") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
    val total = (metadata?.get("expected_files") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
    val partial = metadata?.get("coverage_complete") == JsonPrimitive(false) || metadata?.get("complete") == JsonPrimitive(false)
    val statuses = metadata?.get("file_statuses") as? JsonObject
    val retryable = metadata?.graphCount("retryable_files") ?: statuses?.graphCount("failed") ?: 0
    val jobState = state.indexJob?.graphText("state")
    val jobError = state.indexJob?.graphText("error_code")?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
    val indexedEmpty = map != null && graph.nodes.isEmpty()
    val canContinue = !state.indexingOnGateway && (state.indexCursor != null || state.indexingPaused || state.indexJob?.graphText("state") in setOf("PAUSED", "INTERRUPTED"))
    val queryRecoverable = state.repairAction in setOf("NARROW_GRAPH_QUERY", "RESTART_MAP_SEARCH")
    val blocked = state.error != null && !queryRecoverable || !gatewayConfigured || state.indexOutcomeUnknown
    val label = when {
        !gatewayConfigured -> "Setup needed"
        state.indexOutcomeUnknown -> "Check required"
        state.error != null -> "Needs attention"
        state.indexingOnGateway -> "Building on gateway"
        state.busy -> if (state.operation == "index") "Updating index" else "Checking"
        jobState == "FAILED" -> "Indexing needs attention"
        canContinue -> "Paused"
        graph.nodes.isNotEmpty() -> if (partial) "Partial map" else "Map available"
        indexedEmpty -> "No source links"
        state.result?.get("status") == JsonPrimitive("NOT_INDEXED") -> "Not indexed"
        else -> "Not loaded"
    }
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth().testTag("github_status_repository_map")
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.primary.copy(alpha = .12f)) {
                    Icon(Icons.Outlined.AccountTree, null, Modifier.padding(10.dp).size(24.dp), tint = colors.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (showTitle) Text("Repository map", style = MaterialTheme.typography.titleMedium)
                    Text("${context.owner}/${context.repo}", style = MaterialTheme.typography.bodyMedium)
                }
                if ((state.busy || state.indexingOnGateway && state.error == null) && gatewayConfigured) {
                    TextButton(onClick = onStop, modifier = Modifier.testTag("github_repository_map_stop")) { Text("Stop") }
                } else {
                    TextButton(onClick = onRefresh, enabled = gatewayConfigured, modifier = Modifier.testTag("github_repository_map_refresh")) { Text("Refresh") }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Revision ${context.commitSha.take(10)}",
                        modifier = Modifier.testTag("github_repository_map_revision").semantics { contentDescription = "Repository revision ${context.commitSha}" },
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant
                    )
                    state.gatewayName?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(context.commitSha)) }, modifier = Modifier.testTag("github_repository_map_copy_revision")) {
                    Icon(Icons.Outlined.ContentCopy, "Copy full revision", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(10.dp), color = (if (blocked) colors.error else colors.primary).copy(alpha = .1f)) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp).testTag("github_repository_map_phase"), style = MaterialTheme.typography.labelMedium, color = if (blocked) colors.error else colors.primary)
            }
            when {
                !gatewayConfigured -> RepositoryGraphRepair(
                    "Choose an engineering gateway",
                    "Select the connection that will build and retain this repository’s map.",
                    state,
                    onConfigureGateway,
                    onRefresh,
                    allowRefresh = false
                )
                state.indexOutcomeUnknown -> RepositoryGraphRepair(
                    "Check the saved index first",
                    "The build request may have reached the gateway. Read its saved status before starting another build.",
                    state,
                    null,
                    onRefresh,
                    refreshLabel = "Check saved index"
                )
                state.error != null -> RepositoryGraphRepair(
                    when (state.repairAction) {
                        "CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB" -> "Connect GitHub on your gateway"
                        "REVIEW_SELECTED_GATEWAY_CERTIFICATE" -> "Check the gateway certificate"
                        "RECHECK_SELECTED_REVISION" -> "Check the selected revision"
                        "NARROW_GRAPH_QUERY" -> "Narrow this source search"
                        "RESTART_MAP_SEARCH" -> "Refresh this source search"
                        else -> "The map needs attention"
                    },
                    if (state.repairAction == "CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB") {
                        "Your selected gateway needs its own GitHub connection before it can map this revision. Open gateway settings to review the connection."
                    } else if (queryRecoverable) {
                        "Use the search below to request a smaller result or refresh the map’s first page."
                    } else {
                        "Check the selected gateway, then refresh this map."
                    },
                    state,
                    onConfigureGateway.takeUnless { queryRecoverable },
                    onRefresh
                )
                state.busy || state.indexingOnGateway -> RepositoryGraphProgress(state, processed, total)
                canContinue -> {
                    Text("Indexing is paused", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.indexCursor != null) "Continue from the saved source-file cursor for this revision." else "Review the retained progress, then continue indexing this revision.", style = MaterialTheme.typography.bodyMedium)
                    RepositoryGraphFileProgress(processed, total)
                    Button(onClick = onIndex, modifier = Modifier.fillMaxWidth().testTag("github_repository_map_index")) { Text("Continue indexing") }
                }
                state.searchResult != null && graph.nodes.isEmpty() -> Text("No matching file or symbol was found in this index. Unindexed or dynamic code may still contain a match.", style = MaterialTheme.typography.bodyMedium)
                graph.nodes.isEmpty() -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(84.dp).background(Brush.radialGradient(listOf(colors.primary.copy(alpha = .16f), colors.primary.copy(alpha = 0f))), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AccountTree, null, Modifier.size(42.dp), tint = colors.primary)
                        }
                        Text(if (indexedEmpty) "No source links were returned" else "Explore this revision", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(
                            if (indexedEmpty) "The available index has no file or symbol relationships to show. Source search can help investigate code the index does not cover." else "Build a source index to explore files, symbols and their recorded relationships.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onIndex, modifier = Modifier.fillMaxWidth().testTag("github_repository_map_index")) { Text(if (indexedEmpty) "Update repository index" else "Build repository index") }
                    }
                }
            }
            if (jobState in setOf("FAILED", "PAUSED", "CANCELLED", "INTERRUPTED") && state.error == null && !state.indexOutcomeUnknown) {
                val jobMessage = when (jobError) {
                    "INDEX_ACCESS_REVOKED" -> "The saved index job stopped because repository access changed. Review the gateway connection, then refresh."
                    "INDEX_CONNECTION_UNAVAILABLE" -> "The saved index job stopped after connection retries. Its source progress is retained."
                    "INDEX_BUDGET_REACHED" -> "The saved index job reached its work limit. Continue indexing to process the remaining files."
                    "INDEX_RETRY_OUTCOME_UNKNOWN" -> "The gateway could not confirm that the failed-file retry ran. Review the retained coverage before retrying those files."
                    else -> when (jobState) {
                        "CANCELLED" -> "The saved index job was stopped. Previously indexed sources remain available."
                        "INTERRUPTED" -> "The gateway is recovering its saved index job. Refresh to reconnect to its progress."
                        "PAUSED" -> "The gateway paused its saved index job. Previously indexed sources remain available."
                        else -> "The saved index job failed. Review its retained progress and refresh before continuing."
                    }
                }
                Text(listOfNotNull(jobMessage, jobError).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (jobState == "FAILED") colors.error else colors.onSurfaceVariant, modifier = Modifier.testTag("github_repository_map_job_state"))
            }
            if (metadata != null) {
                val categories = listOf("indexed" to "indexed", "partial" to "partial", "unsupported" to "unsupported", "failed" to "failed")
                if (statuses != null) Text(categories.joinToString(" · ") { (key, label) -> "${statuses.graphCount(key) ?: 0} $label" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("github_repository_map_coverage"))
                if (metadata["processing_complete"] == JsonPrimitive(true) && partial) Text("Processing finished with incomplete source coverage. Missing relationships remain unknown.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (retryable > 0 && !blocked && !state.busy && !state.indexingOnGateway) OutlinedButton(onClick = onRetryFailed, modifier = Modifier.testTag("github_repository_map_retry_failed")) { Text("Retry $retryable failed or recoverable files") }
                if ((statuses?.graphCount("unsupported") ?: 0) > 0) Text("Unsupported files remain listed in coverage. Files above the size limit need a source read; unavailable parsers can be retried after updating the gateway.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (state.modelAccess != "NOT_CHECKED") {
                Text(
                    if (state.modelAccess == "PRIVATE_PROFILE_ELIGIBLE") "Your private AI profile is eligible to use this map. Actual use is recorded with each retrieval." else "Map browsing is available, but the selected AI profile cannot receive private graph context. Select a private profile to use the map in AI requests.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("github_repository_map_model_access")
                )
            }
            if (gatewayConfigured && (map != null || metadata != null)) {
                OutlinedTextField(query, { query = it.take(2000) }, label = { Text("Find a file or symbol") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_repository_map_search"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSearch(query) }, enabled = !state.busy && !blocked, modifier = Modifier.testTag("github_repository_map_search_submit")) { Text("Search index") }
                    if (state.mapQuery.isNotEmpty() || state.mapFocusPath != null) {
                        TextButton(onClick = {
                            query = ""
                            onSearch("")
                        }, enabled = !state.busy && !blocked) { Text("All files") }
                    }
                }
                state.mapFocusPath?.let { Text("Connections around $it", style = MaterialTheme.typography.labelMedium) }
                if (graph.nodes.isNotEmpty()) {
                    val totalMatches = map?.graphCount(if (state.mapQuery.isNotEmpty()) "total_matches" else "total_files")
                    val totalLinks = map?.graphCount("total_relationships")
                    Text("${graph.nodes.size} loaded sources" + totalMatches?.let { if (state.mapQuery.isNotEmpty()) " · $it matching sources" else " · $it sources in this view" }.orEmpty() + " · ${graph.edges.size} loaded links" + totalLinks?.let { " of $it" }.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    GraphMiniMap(visible.nodes, graph.edges, selectedId = selectedNode?.graphText("id")) { node ->
                        selectedNode = node
                        if (!state.busy && !blocked) onSelect(node)
                    }
                    if (visible.omittedNodes > 0 || visible.externalConnections > 0) Text("${visible.omittedNodes} loaded sources and ${visible.externalConnections} connections extend beyond this diagram. Select a source below to center its neighborhood.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("github_repository_map_external_links"))
                    if (visible.omittedNodes > 0) {
                        graph.nodes.forEach { node ->
                            TextButton(onClick = { selectedNode = node }, modifier = Modifier.fillMaxWidth()) { Text(node.graphText("qualified", node.graphText("name"))) }
                        }
                    }
                    if (visible.edges.isNotEmpty()) Text("Arrows follow indexed source links. Select a source to inspect its connections.", Modifier.testTag("github_repository_map_legend"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    selectedNode?.let { node ->
                        RepositoryGraphSourceDetail(context, node, state, graph)
                        TextButton(onClick = { onNeighborhood(node) }, enabled = !state.busy && !blocked, modifier = Modifier.testTag("github_repository_map_neighborhood")) { Text("Explore this source’s connections") }
                    }
                }
                if (state.mapPage > 0 || state.mapNextCursor != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onPreviousPage, enabled = state.mapPage > 0 && !state.busy && !blocked, modifier = Modifier.testTag("github_repository_map_previous")) { Text("Previous") }
                        Text("Page ${state.mapPage + 1}", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = onNextPage, enabled = state.mapNextCursor != null && !state.busy && !blocked, modifier = Modifier.testTag("github_repository_map_next")) { Text("Next") }
                    }
                }
                if (map?.get("stale") == JsonPrimitive(true)) Text("The branch has moved. This map remains pinned to the revision above.", color = colors.error, style = MaterialTheme.typography.bodySmall)
                if (partial) Text("This index has incomplete source coverage. A missing relationship is not proof that the source has no connection.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (map?.get("has_more") == JsonPrimitive(true)) Text("More results are available on the next page.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RepositoryGraphRepair(title: String, message: String, state: RepositoryGraphUiState, onConfigureGateway: (() -> Unit)?, onRefresh: () -> Unit, allowRefresh: Boolean = true, refreshLabel: String = "Check connection again") {
    var details by remember(state.error) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f), modifier = Modifier.fillMaxWidth().testTag("github_repository_map_repair")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            onConfigureGateway?.let { configure -> OutlinedButton(onClick = configure, modifier = Modifier.fillMaxWidth().testTag("github_repository_map_configure")) { Text("Open gateway settings") } }
            if (allowRefresh) TextButton(onClick = onRefresh, modifier = Modifier.testTag("github_repository_map_recheck")) { Text(refreshLabel) }
            state.error?.takeIf(String::isNotBlank)?.let { error ->
                TextButton(onClick = { details = !details }, modifier = Modifier.testTag("github_repository_map_error_details")) { Text(if (details) "Hide details" else "Connection details") }
                if (details) SelectionContainer { Text(listOfNotNull(state.errorCode, error).joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun RepositoryGraphProgress(state: RepositoryGraphUiState, processed: Int?, total: Int?) {
    val colors = MaterialTheme.colorScheme
    val progress = if (processed != null && total != null && total > 0 && processed <= total && (state.operation == "index" || state.indexingOnGateway)) processed.toFloat() / total else null
    val phase = rememberGitHubProgressMotion(progress == null)
    val message = when {
        state.indexingOnGateway -> "Building on your gateway"
        state.operation == "index" -> "Building the source map"
        state.indexOutcomeUnknown -> "Checking the saved index"
        state.operation == "node" -> "Reading source details"
        else -> "Loading source relationships"
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("github_repository_map_progress").semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(5.dp).testTag("github_repository_map_progress_bar").semantics { progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate }) {
            drawRoundRect(colors.primary.copy(alpha = .12f), cornerRadius = CornerRadius(size.height / 2))
            if (progress != null) {
                drawRoundRect(colors.primary, size = size.copy(width = size.width * progress), cornerRadius = CornerRadius(size.height / 2))
            } else {
                drawRoundRect(githubProgressBrush(size.width, phase.value, colors.primary.copy(alpha = .12f), colors.primary), cornerRadius = CornerRadius(size.height / 2))
            }
        }
        RepositoryGraphFileProgress(processed, total)
        Text(if (state.indexingOnGateway) "You can close this screen. The saved index job continues on your gateway." else "The map stays pinned to the selected revision.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun RepositoryGraphFileProgress(processed: Int?, total: Int?) {
    if (processed != null) Text(if (total != null && processed <= total) "$processed of $total files processed" else "$processed files processed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RepositoryGraphSourceDetail(context: GitHubConversationContext, node: JsonObject, state: RepositoryGraphUiState, graph: RepositoryGraphPresentation) {
    val detail = state.result?.takeIf { state.resultOperation == "node" }?.graphObjects("nodes")?.firstOrNull {
        it.graphText("id") == node.graphText("id") || (node.graphText("kind") == "file" && it.graphText("kind") == "file" && it.graphText("path") == node.graphText("path"))
    } ?: node
    val uri = LocalUriHandler.current
    val path = detail.graphText("path")
    var excerpt by remember(node) { mutableStateOf(false) }
    var allLinks by remember(node) { mutableStateOf(false) }
    val links = remember(graph, node) { graph.relationships(node.graphText("id")) }
    val byId = remember(graph) { graph.nodes.associateBy { it.graphText("id") } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("github_repository_map_selection")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail.graphText("qualified", detail.graphText("name")), style = MaterialTheme.typography.titleSmall)
            if (path.isNotBlank()) SelectionContainer { Text(path + detail.graphText("line").takeIf(String::isNotBlank)?.let { ":$it" }.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            (if (allLinks) links else links.take(6)).forEach { edge ->
                val source = byId[edge.graphText("source")]?.graphText("name") ?: edge.graphText("source")
                val target = byId[edge.graphText("target")]?.graphText("name") ?: edge.graphText("target")
                Text("$source → $target · ${edge.graphText("relation", "source link").replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            }
            if (links.size > 6) TextButton(onClick = { allLinks = !allLinks }) { Text(if (allLinks) "Fewer links" else "All ${links.size} source links") }
            if (detail.graphText("excerpt").isNotBlank()) {
                TextButton(onClick = { excerpt = !excerpt }) { Text(if (excerpt) "Hide source excerpt" else "Read source excerpt") }
                if (excerpt) SelectionContainer { Text(detail.graphText("excerpt"), Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            }
            if (path.isNotBlank() && path.split('/').none { it == ".." || it.isBlank() }) {
                TextButton(onClick = { uri.openUri("https://github.com/${context.owner}/${context.repo}/blob/${context.commitSha}/${android.net.Uri.encode(path, "/")}") }) { Text("Open source at this revision") }
            }
        }
    }
}

private fun JsonObject.graphCount(name: String): Int? = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
