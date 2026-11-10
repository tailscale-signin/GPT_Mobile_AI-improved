package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog as Dialog
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdrop
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The Status tab owns a visual, source-backed map. Loading the map does not
 * authorize writes, infer live code execution or synthesize missing edges.
 * The selected gateway remains the only graph data source.
 */
@Composable
fun RepositoryGraphStatusMap(
    context: GitHubConversationContext,
    profile: PlatformV2?,
    model: RepositoryGraphViewModel = hiltViewModel(key = "github-status-repository-map"),
    onConfigureGateway: (() -> Unit)? = null
) {
    val state = observeRepositoryGraph(context, profile, model)
    RepositoryGraphStatusContent(
        context,
        state,
        onRefresh = { model.request("status") },
        onIndex = { model.request("index") },
        onStop = model::stop,
        onSelect = { node -> model.request("node", symbol = node.text("id")) },
        onConfigureGateway = onConfigureGateway,
        gatewayConfigured = GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)
    )
}

@Composable
private fun observeRepositoryGraph(context: GitHubConversationContext, profile: PlatformV2?, model: RepositoryGraphViewModel): RepositoryGraphUiState {
    LaunchedEffect(context, profile) { model.bind(context, profile?.uid, profile) }
    DisposableEffect(context, profile, model) { onDispose(model::stop) }
    val observed by model.state.collectAsStateWithLifecycle()
    return observed.takeIf { it.boundContext == context && it.gatewayProfileUid == profile?.uid && model.isBoundTo(context, profile?.uid, profile) && it == model.state.value }
        ?: RepositoryGraphUiState(boundContext = context, gatewayProfileUid = profile?.uid, gatewayName = profile?.name, busy = true)
}

/** A bounded repository explorer opened from GitHub workspace settings. */
@Composable
fun RepositoryGraphPanel(context: GitHubConversationContext, profile: PlatformV2?, onDismiss: () -> Unit, model: RepositoryGraphViewModel = hiltViewModel(key = "github-repository-graph-explorer"), onConfigureGateway: (() -> Unit)? = null) {
    val state = observeRepositoryGraph(context, profile, model)
    val gatewayConfigured = GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)
    val canInspect = gatewayConfigured && !state.busy && !state.indexOutcomeUnknown && state.error == null
    var showTools by remember(context, profile) { mutableStateOf(false) }
    var query by remember(context, profile) { mutableStateOf("") }
    var target by remember(context, profile) { mutableStateOf("") }
    var selected by remember(context, profile) { mutableStateOf<JsonObject?>(null) }
    val result = state.result ?: JsonObject(emptyMap())
    val metadata = result["graph"] as? JsonObject
    val graph = remember(result) { repositoryGraphPresentation(result) }
    val nodes = graph.nodes
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = {
        model.stop()
        onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.glassBackdrop(androidx.compose.foundation.shape.RoundedCornerShape(0.dp), captureAsPopup = true),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Repository map", style = MaterialTheme.typography.titleLarge)
                        }
                        TextButton(onClick = {
                            model.stop()
                            onDismiss()
                        }) { Text("Close") }
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    RepositoryGraphStatusContent(
                        context,
                        state,
                        onRefresh = { model.request("status") },
                        onIndex = { model.request("index") },
                        onStop = model::stop,
                        onSelect = { model.request("node", symbol = it.text("id")) },
                        onConfigureGateway = onConfigureGateway,
                        gatewayConfigured = gatewayConfigured,
                        showTitle = false
                    )
                }
                if (gatewayConfigured) {
                    item {
                        TextButton(onClick = { showTools = !showTools }, modifier = Modifier.testTag("github_repository_map_source_tools")) { Text(if (showTools) "Hide source tools" else "Explore source tools") }
                    }
                }
                if (showTools && gatewayConfigured) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Index" to "index", "Health" to "status", "Architecture" to "architecture", "PR overlap" to "prs", "Memory" to "notes", "Requirements" to "requirements", "Docs" to "wiki", "Metrics" to "metrics").forEach { (label, action) ->
                                TextButton(onClick = {
                                    selected = null
                                    model.request(action)
                                }, enabled = canInspect) { Text(label) }
                            }
                            if (state.busy) TextButton(onClick = model::stop) { Text("Stop") }
                        }
                        Text("Source relationships guide investigation. Test results and current GitHub checks remain authoritative.", style = MaterialTheme.typography.bodySmall)
                        if (result["stale"] == JsonPrimitive(true)) Text("The branch has moved. This map describes ${context.commitSha.take(10)}.", color = MaterialTheme.colorScheme.error)
                        if (result["truncated"] == JsonPrimitive(true)) Text("Showing a bounded selection. Search for a narrower symbol or subsystem.")
                        if (metadata != null) Text("${metadata.text("processed_files")} / ${metadata.text("expected_files")} files · ${metadata.text("nodes", "—")} symbols · ${metadata.text("unresolved_references", "—")} unresolved", style = MaterialTheme.typography.bodySmall)
                        if (result.text("status") == "NOT_INDEXED") Text("Index this revision to build its local repository map.")
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        OutlinedTextField(value = query, onValueChange = { query = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Question, symbol, or source path") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                selected = null
                                model.request("query", query = query)
                            }, enabled = canInspect && query.isNotBlank()) { Text("Search") }
                            TextButton(onClick = { model.request("node", symbol = query) }, enabled = canInspect && query.isNotBlank()) { Text("Inspect") }
                            TextButton(onClick = { model.request("impact", symbol = query) }, enabled = canInspect && query.isNotBlank()) { Text("Impact & tests") }
                        }
                        OutlinedTextField(value = target, onValueChange = { target = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Target symbol for a dependency path") }, singleLine = true)
                        TextButton(onClick = {
                            model.request(
                                "path",
                                options = buildJsonObject {
                                    put("source", query)
                                    put("target", target)
                                    put("depth", 6)
                                }
                            )
                        }, enabled = canInspect && query.isNotBlank() && target.isNotBlank()) { Text("Trace path") }
                    }
                    if (nodes.isNotEmpty()) {
                        item {
                            GraphMiniMap(nodes.take(12), graph.edges, selectedId = selected?.text("id")) { node ->
                                selected = node
                                if (canInspect) model.request("node", symbol = node.text("id"))
                            }
                        }
                        items(nodes, key = { it.text("id") }) { node ->
                            Card(Modifier.fillMaxWidth().clickable { selected = node }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(node.text("qualified"), style = MaterialTheme.typography.titleSmall)
                                    Text(node.text("path") + node.text("line").takeIf(String::isNotBlank)?.let { ":$it" }.orEmpty() + " · ${node.text("kind")}", style = MaterialTheme.typography.bodySmall)
                                    if (selected?.text("id") == node.text("id")) {
                                        Text(node.text("excerpt"), style = MaterialTheme.typography.bodySmall)
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            val prefix = "https://github.com/${context.owner}/${context.repo}/blob/${context.commitSha}/"
                                            val source = node.text("url").takeIf { it.startsWith(prefix) }
                                                ?: node.text("path").takeIf { it.isNotBlank() && it.split('/').none { segment -> segment == ".." || segment.isBlank() } }?.let { prefix + android.net.Uri.encode(it, "/") }
                                            TextButton(onClick = { source?.let(uri::openUri) }, enabled = source != null) { Text("Source") }
                                            TextButton(onClick = { model.request("callers", symbol = node.text("id")) }, enabled = canInspect) { Text("Callers") }
                                            TextButton(onClick = { model.request("callees", symbol = node.text("id")) }, enabled = canInspect) { Text("Callees") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(result.objects("candidates")) { node ->
                        TextButton(onClick = { model.request("node", symbol = node.text("id")) }, enabled = canInspect) { Text("${node.text("qualified")} · ${node.text("path")}:${node.text("line")}") }
                    }
                    items(result.objects("edges")) { edge ->
                        val byId = nodes.associateBy { it.text("id") }
                        Text("${byId[edge.text("source")]?.text("name") ?: edge.text("source")} → ${byId[edge.text("target")]?.text("name") ?: edge.text("target")} · ${edge.text("relation")} · ${edge.text("confidence")}", style = MaterialTheme.typography.bodySmall)
                    }
                    items(result.objects("communities").take(30)) { community ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(community.text("name"), style = MaterialTheme.typography.titleSmall)
                                (community["files"] as? JsonArray).orEmpty().take(8).forEach { file ->
                                    val path = (file as? JsonPrimitive)?.content.orEmpty()
                                    TextButton(onClick = { model.request("node", symbol = path) }, enabled = canInspect) { Text(path) }
                                }
                            }
                        }
                    }
                    items(result.objects("tests")) { test -> Text("${test.text("path")} · ${test.text("reason")} · ${test.text("execution_status")}") }
                    items(result.objects("paths")) { path -> TextButton(onClick = { model.request("node", symbol = path.text("path")) }, enabled = canInspect) { Text("${path.text("path")} · ${path.text("distance")} hop(s)") } }
                    items(result.objects("notes")) { note ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(note.text("kind"), style = MaterialTheme.typography.titleSmall)
                                Text(note.text("content"), style = MaterialTheme.typography.bodySmall)
                                if (note["review_required"] == JsonPrimitive(true)) Text("Supporting source changed; review this note.")
                                if (note["superseded"] == JsonPrimitive(true)) Text("Superseded by a newer note.")
                            }
                        }
                    }
                    items(result.objects("measurements")) { row -> Text("${row.text("operation")}: ${row.text("requests")} calls · ${row.text("mean_latency_ms")} ms average · ${row.text("output_bytes")} bytes") }
                    items(result.objects("issues")) { issue -> Text("${issue.text("path")}: ${issue.text("status")} · ${issue["warnings"]}", style = MaterialTheme.typography.bodySmall) }
                    items(result.objects("overlaps")) { overlap -> Text("PR #${overlap.text("left")} and #${overlap.text("right")} affect related code: ${overlap["related_paths"]}") }
                    items(result.objects("requirements")) { requirement -> Text("${requirement.text("content")}\n${requirement["validation"]}") }
                    if (result["markdown"] is JsonPrimitive) {
                        item {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(result.text("markdown"))) }) { Text("Copy architecture document") }
                            Text(result.text("markdown"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.resultOperation == "metrics") item { Text("Answer accuracy: ${result.text("accuracy")}\nToken savings: ${result.text("token_savings")}", style = MaterialTheme.typography.bodySmall) }
                    if (nodes.isEmpty() && result.text("status") in setOf("NOT_FOUND_IN_INDEX", "NO_PATH_IN_INDEX")) item { Text("No matching relationship in this index. Use source search to investigate unindexed or dynamic code.") }
                }
            }
        }
    }
}

private fun JsonObject.text(name: String, default: String = ""): String = (get(name) as? JsonPrimitive)?.contentOrNull ?: default
private fun JsonObject.objects(name: String): List<JsonObject> = (get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
