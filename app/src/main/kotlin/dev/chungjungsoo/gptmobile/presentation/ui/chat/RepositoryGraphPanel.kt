package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** An optional, bounded repository explorer embedded in GitHub chat. */
@Composable
fun RepositoryGraphPanel(context: GitHubConversationContext, profile: PlatformV2?, model: RepositoryGraphViewModel = hiltViewModel()) {
    if (!GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) return
    var open by remember(context) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Repository map") }
    if (!open) return
    LaunchedEffect(context) { model.bind(context) }
    val state by model.state.collectAsStateWithLifecycle()
    var query by remember(context) { mutableStateOf("") }
    var target by remember(context) { mutableStateOf("") }
    var selected by remember(context) { mutableStateOf<JsonObject?>(null) }
    val result = state.result ?: JsonObject(emptyMap())
    val metadata = result["graph"] as? JsonObject
    val nodes = result.objects("nodes")
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = {
        model.stop()
        open = false
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Repository map", style = MaterialTheme.typography.titleLarge)
                            Text("${context.repo} · ${context.commitSha.take(10)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            model.stop()
                            open = false
                        }) { Text("Close") }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Index" to "index", "Health" to "status", "Architecture" to "architecture", "PR overlap" to "prs", "Memory" to "notes", "Requirements" to "requirements", "Docs" to "wiki", "Metrics" to "metrics").forEach { (label, action) ->
                            TextButton(onClick = {
                                selected = null
                                model.request(action)
                            }, enabled = !state.busy) { Text(label) }
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
                        }, enabled = !state.busy && query.isNotBlank()) { Text("Search") }
                        TextButton(onClick = { model.request("node", symbol = query) }, enabled = !state.busy && query.isNotBlank()) { Text("Inspect") }
                        TextButton(onClick = { model.request("impact", symbol = query) }, enabled = !state.busy && query.isNotBlank()) { Text("Impact & tests") }
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
                    }, enabled = !state.busy && query.isNotBlank() && target.isNotBlank()) { Text("Trace path") }
                }
                if (nodes.isNotEmpty()) {
                    item {
                        GraphMiniMap(nodes.take(12), result.objects("edges")) { node ->
                            selected = node
                            model.request("node", symbol = node.text("id"))
                        }
                    }
                    items(nodes, key = { it.text("id") }) { node ->
                        Card(Modifier.fillMaxWidth().clickable { selected = node }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(node.text("qualified"), style = MaterialTheme.typography.titleSmall)
                                Text("${node.text("path")}:${node.text("line")} · ${node.text("kind")}", style = MaterialTheme.typography.bodySmall)
                                if (selected?.text("id") == node.text("id")) {
                                    Text(node.text("excerpt"), style = MaterialTheme.typography.bodySmall)
                                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                                        TextButton(onClick = {
                                            val source = node.text("url")
                                            if (source.startsWith("https://github.com/${context.owner}/${context.repo}/blob/${context.commitSha}/")) uri.openUri(source)
                                        }) { Text("Source") }
                                        TextButton(onClick = { model.request("callers", symbol = node.text("id")) }, enabled = !state.busy) { Text("Callers") }
                                        TextButton(onClick = { model.request("callees", symbol = node.text("id")) }, enabled = !state.busy) { Text("Callees") }
                                    }
                                }
                            }
                        }
                    }
                }
                items(result.objects("candidates")) { node ->
                    TextButton(onClick = { model.request("node", symbol = node.text("id")) }) { Text("${node.text("qualified")} · ${node.text("path")}:${node.text("line")}") }
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
                                TextButton(onClick = { model.request("node", symbol = path) }, enabled = !state.busy) { Text(path) }
                            }
                        }
                    }
                }
                items(result.objects("tests")) { test -> Text("${test.text("path")} · ${test.text("reason")} · ${test.text("execution_status")}") }
                items(result.objects("paths")) { path -> TextButton(onClick = { model.request("node", symbol = path.text("path")) }) { Text("${path.text("path")} · ${path.text("distance")} hop(s)") } }
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
                if (state.operation == "metrics") item { Text("Answer accuracy: ${result.text("accuracy")}\nToken savings: ${result.text("token_savings")}", style = MaterialTheme.typography.bodySmall) }
                if (nodes.isEmpty() && result.text("status") in setOf("NOT_FOUND_IN_INDEX", "NO_PATH_IN_INDEX")) item { Text("No matching relationship in this index. Use source search to investigate unindexed or dynamic code.") }
            }
        }
    }
}

@Composable
private fun GraphMiniMap(nodes: List<JsonObject>, edges: List<JsonObject>, onSelect: (JsonObject) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.outlineVariant
    val selectedIds = nodes.map { it.text("id") }
    Box(Modifier.fillMaxWidth().height(180.dp)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(nodes) {
                detectTapGestures { tap ->
                    val nearest = nodes.indices.minByOrNull { i ->
                        val angle = 2 * Math.PI * i / nodes.size.coerceAtLeast(1)
                        val point = Offset(size.width / 2f + cos(angle).toFloat() * size.width * .38f, size.height / 2f + sin(angle).toFloat() * size.height * .35f)
                        (point - tap).getDistance()
                    }
                    nearest?.let { onSelect(nodes[it]) }
                }
            }
        ) {
            val points = nodes.indices.map { i ->
                val angle = 2 * Math.PI * i / nodes.size.coerceAtLeast(1)
                Offset(size.width / 2 + cos(angle).toFloat() * size.width * .38f, size.height / 2 + sin(angle).toFloat() * size.height * .35f)
            }
            edges.forEach { edge ->
                val a = selectedIds.indexOf(edge.text("source"))
                val b = selectedIds.indexOf(edge.text("target"))
                if (a >= 0 && b >= 0) drawLine(secondary, points[a], points[b], strokeWidth = 2.dp.toPx())
            }
            points.forEach { drawCircle(primary, radius = 7.dp.toPx(), center = it) }
        }
    }
}

private fun JsonObject.text(name: String, default: String = ""): String = (get(name) as? JsonPrimitive)?.contentOrNull ?: default
private fun JsonObject.objects(name: String): List<JsonObject> = (get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
