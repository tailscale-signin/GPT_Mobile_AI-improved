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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.cos
import kotlin.math.sin
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
    model: RepositoryGraphViewModel = hiltViewModel()
) {
    if (!GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Repository network", style = MaterialTheme.typography.titleMedium)
                Text("Select an authorized private gateway profile to explore a source-backed repository graph.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    LaunchedEffect(context) { model.bind(context) }
    DisposableEffect(context, model) { onDispose(model::stop) }
    val state by model.state.collectAsStateWithLifecycle()
    var architectureRequested by remember(context) { mutableStateOf(false) }
    var capturedMap by remember(context) { mutableStateOf<JsonObject?>(null) }
    var selectedNode by remember(context) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(context, state.busy, state.operation, state.result) {
        if (!state.busy && state.operation == "status" && state.result != null && (!architectureRequested || capturedMap == null)) {
            architectureRequested = true
            if (state.result?.get("status") != JsonPrimitive("NOT_INDEXED")) model.request("architecture")
        } else if (!state.busy && state.operation == "architecture" && state.result != null) {
            capturedMap = state.result
        }
    }
    val map = capturedMap
    val sourceNodes = map?.objects("nodes").orEmpty()
    val communities = map?.objects("communities").orEmpty()
    // Communities are drawn as distinct bubbles when the architecture endpoint
    // supplies community data but no symbol-level nodes.
    val bubbles = if (sourceNodes.isNotEmpty()) sourceNodes else communities.mapIndexed { index, community ->
        buildJsonObject {
            put("id", community.text("id", "community-$index"))
            put("name", community.text("name", "Subsystem ${index + 1}"))
            put("qualified", community.text("name", "Subsystem ${index + 1}"))
            put("kind", "community")
            put("file_count", (community["files"] as? JsonArray)?.size ?: 0)
        }
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("github_status_repository_map")
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Repository network", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${context.owner}/${context.repo} · ${context.commitSha.take(10)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = {
                    selectedNode = null
                    architectureRequested = true
                    model.request("architecture")
                }, enabled = !state.busy) { Text("Refresh") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            GraphMiniMap(bubbles.take(36), map?.objects("edges").orEmpty()) { node ->
                selectedNode = node
                if (sourceNodes.isNotEmpty() && node.text("id").isNotBlank() && !state.busy) {
                    model.request("node", symbol = node.text("id"))
                }
            }
            if (bubbles.isEmpty()) {
                Text(
                    "The selected revision has no loaded graph nodes. This is a visual placeholder, not a verified dependency map.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    selectedNode = null
                    capturedMap = null
                    model.request("index")
                }, enabled = !state.busy) { Text("Build repository index") }
            } else {
                Text(
                    "${bubbles.size.coerceAtMost(36)} source-backed ${if (sourceNodes.isNotEmpty()) "symbols" else "subsystems"} · tap a bubble to inspect",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            selectedNode?.let { node ->
                Text(node.text("qualified", node.text("name")), style = MaterialTheme.typography.titleSmall)
                Text(node.text("path", "Graph subsystem") + (node.text("line").takeIf(String::isNotBlank)?.let { ":$it" } ?: node.text("file_count").takeIf { it != "0" }?.let { " · $it files" }.orEmpty()), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (map?.get("stale") == JsonPrimitive(true)) Text("This graph is stale; refresh the selected branch revision.", color = MaterialTheme.colorScheme.error)
            if (map?.get("truncated") == JsonPrimitive(true) || bubbles.size > 36) Text("A bounded selection is shown; unrendered relationships are not claimed.", style = MaterialTheme.typography.bodySmall)
            Text("Source relationships are informational. Actual build, test and GitHub check results appear below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A bounded repository explorer opened from GitHub workspace settings. */
@Composable
fun RepositoryGraphPanel(context: GitHubConversationContext, profile: PlatformV2?, onDismiss: () -> Unit, model: RepositoryGraphViewModel = hiltViewModel()) {
    if (!GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) return
    LaunchedEffect(context) { model.bind(context) }
    DisposableEffect(context, model) { onDispose(model::stop) }
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
                            Text("${context.repo} · ${context.commitSha.take(10)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            model.stop()
                            onDismiss()
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
internal fun GraphMiniMap(nodes: List<JsonObject>, edges: List<JsonObject>, onSelect: (JsonObject) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val foreground = MaterialTheme.colorScheme.onSurface
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val labels = nodes.map { it.text("name", it.text("qualified", it.text("id"))).substringAfterLast('.').take(13) }
    val ids = nodes.map { it.text("id") }
    Canvas(
        Modifier.fillMaxWidth().height(310.dp)
            .testTag("github_repository_bubble_network")
            .semantics { contentDescription = "Interactive repository network with ${nodes.size} source-backed bubbles" }
            .pointerInput(nodes) {
                detectTapGestures { position ->
                    if (nodes.isEmpty()) return@detectTapGestures
                    val selected = nodes.indices.minByOrNull { i ->
                        val ringCount = if (nodes.size > 12) if (i >= 12) nodes.size - 12 else 12 else nodes.size.coerceAtLeast(1)
                        val slot = if (nodes.size > 12 && i >= 12) i - 12 else i
                        val angle = 2 * Math.PI * slot / ringCount - Math.PI / 2
                        val ring = if (nodes.size > 12 && i >= 12) 0.44f else if (nodes.size > 6) 0.34f else 0.30f
                        val point = Offset(
                            size.width / 2f + cos(angle).toFloat() * size.width * ring,
                            size.height / 2f + sin(angle).toFloat() * size.height * ring
                        )
                        (point - position).getDistance()
                    } ?: return@detectTapGestures
                    onSelect(nodes[selected])
                }
            }
    ) {
        val points = nodes.indices.map { i ->
            val ringCount = if (nodes.size > 12) if (i >= 12) nodes.size - 12 else 12 else nodes.size.coerceAtLeast(1)
            val slot = if (nodes.size > 12 && i >= 12) i - 12 else i
            val angle = 2 * Math.PI * slot / ringCount - Math.PI / 2
            val ring = if (nodes.size > 12 && i >= 12) 0.44f else if (nodes.size > 6) 0.34f else 0.30f
            Offset(
                size.width / 2 + cos(angle).toFloat() * size.width * ring,
                size.height / 2 + sin(angle).toFloat() * size.height * ring
            )
        }
        val center = Offset(size.width / 2, size.height / 2)
        // Spokes visualize groups around the repository; only edges returned by
        // the graph service are drawn as dependency relationships.
        points.forEach { drawLine(outline.copy(alpha = .30f), center, it, strokeWidth = 1.dp.toPx()) }
        edges.forEach { edge ->
            val a = ids.indexOf(edge.text("source"))
            val b = ids.indexOf(edge.text("target"))
            if (a >= 0 && b >= 0) drawLine(tertiary.copy(alpha = .8f), points[a], points[b], strokeWidth = 2.dp.toPx())
        }
        drawCircle(primary.copy(alpha = .16f), radius = 39.dp.toPx(), center = center)
        drawCircle(primary, radius = 23.dp.toPx(), center = center)
        points.forEachIndexed { i, point ->
            val color = when (nodes[i].text("kind").lowercase()) {
                "community", "module", "package" -> tertiary
                "class", "interface", "function", "method" -> primary
                else -> secondary
            }
            val radius = if (nodes.size > 22) 20.dp.toPx() else 26.dp.toPx()
            drawCircle(color.copy(alpha = .14f), radius = radius + 6.dp.toPx(), center = point)
            drawCircle(color.copy(alpha = .88f), radius = radius, center = point)
        }
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 10.dp.toPx()
                isFakeBoldText = true
            }
            canvas.nativeCanvas.drawText("REPO", center.x, center.y + 4.dp.toPx(), paint.apply { color = onPrimary.toArgb() })
            paint.color = foreground.toArgb()
            points.forEachIndexed { i, point ->
                canvas.nativeCanvas.drawText(labels[i], point.x, point.y + 35.dp.toPx(), paint)
            }
        }
    }
}

private fun JsonObject.text(name: String, default: String = ""): String = (get(name) as? JsonPrimitive)?.contentOrNull ?: default
private fun JsonObject.objects(name: String): List<JsonObject> = (get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
