package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.engine.ResearchEngineRegistry
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ResearchEngineHealthPanel(disabledEngineIds: Set<String> = emptySet(), onEngineEnabled: ((String, Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(context) { ResearchTelemetryStore(File(context.filesDir, "research-telemetry")) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                exportStatus = try {
                    withContext(Dispatchers.IO) { store.writeExport(requireNotNull(context.contentResolver.openOutputStream(uri))) }
                    "Native engine events exported. No queries or source text are included."
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    "Export could not finish. Choose a writable destination and retry."
                }
            }
        }
    }
    TextButton(onClick = { export.launch("research-engine-events.json") }) { Text("Export native engine events") }
    exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    var since by remember { mutableStateOf(0L) }
    var rollups by remember { mutableStateOf(emptyMap<String, dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchEngineRollup>()) }
    var latencies by remember { mutableStateOf(emptyMap<String, Pair<Long?, Long?>>()) }
    var selectedEngine by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(emptyList<dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(selectedEngine, retry) {
        attempts = selectedEngine?.let { withContext(Dispatchers.IO) { store.recent(it) } }.orEmpty()
    }
    LaunchedEffect(since, retry) {
        loading = true
        error = false
        try {
            rollups = withContext(Dispatchers.IO) { ResearchEngineRegistry.entries.filter { it.ready }.associate { it.id to store.rollup(it.id, since) } }
            latencies = withContext(Dispatchers.IO) { ResearchEngineRegistry.entries.filter { it.ready }.associate { it.id to store.latency(it.id, since) } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }
    if (loading) Text("Loading engine history…")
    if (error) {
        Text("Engine history is unavailable. Research settings remain editable.")
        TextButton(onClick = { retry++ }) { Text("Retry") }
    }
    Row {
        TextButton(onClick = { since = 0 }) { Text("Retained history") }
        TextButton(onClick = { since = System.currentTimeMillis() - 86_400_000 }) { Text("24 hours") }
        TextButton(onClick = { since = System.currentTimeMillis() - 7 * 86_400_000L }) { Text("7 days") }
        TextButton(onClick = { since = System.currentTimeMillis() - 30 * 86_400_000L }) { Text("30 days") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ResearchEngineRegistry.entries.filter { it.ready }.forEach { engine ->
            val health = rollups[engine.id]
            if (onEngineEnabled != null && engine.id in ResearchEngineRegistry.searchableIds) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Enable ${engine.name}")
                    androidx.compose.material3.Switch(engine.id !in disabledEngineIds, { onEngineEnabled(engine.id, it) })
                }
            }
            Text(engine.name + " · " + engine.capability, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
            Text(
                if (health == null || health.attempts == 0) {
                    "No completed requests · Useful rate unavailable"
                } else {
                    "${health.successes}/${health.attempts} useful outcomes · ${health.empty} empty · ${health.canceled} canceled · ${health.failures} failures · ${health.transportSuccesses} HTTP completions · ${health.transportFailures} HTTP failures · ${health.transportCanceled} transport cancellations${if (health.lowSample) " · Low sample" else ""}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (health != null && health.attempts > 0) {
                ResearchOutcomeBar(engine.name, health)
                Text("Useful rate: ${"%.1f".format(health.successPercent)}% · definition v2 · completed useful + empty + failed", style = MaterialTheme.typography.bodySmall)
            }
            latencies[engine.id]?.let { (p50, p95) -> Text("Successful live latency · p50 ${p50?.let { "$it ms" } ?: "Unknown"} · p95 ${p95?.let { "$it ms" } ?: "Unknown"}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { selectedEngine = engine.id }) { Text("Recent operation receipts") }
            Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
        }
        Text("Counts are retained observations, not scientific accuracy. HTTP completion, useful parsed results and article appraisal are separate. Historical events pruned before durable receipts were introduced cannot be reconstructed.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirmClear = true }) { Text("Clear research statistics") }
        TextButton(onClick = { pending = !pending }) { Text(if (pending) "Hide unimplemented integrations" else "Unavailable integrations (not queued)") }
        if (pending) {
            ResearchEngineRegistry.entries.filterNot { it.ready }.forEach { engine ->
                Text("${engine.name} · ${if (engine.id == "google_scholar") "Browser-assisted" else "Adapter not ready"}", style = MaterialTheme.typography.labelLarge)
                Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear research statistics?") }, text = { Text("Starts a new measurement epoch. Article evidence is retained; older restored receipts will not reappear in totals.") }, confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.clearStatistics() }
                        retry++
                    } catch (_: Exception) {
                        error = true
                    }
                }
            }) { Text("Clear statistics") }
        }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    }
    selectedEngine?.let { engine ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { selectedEngine = null }, title = { Text("$engine · Recent receipts") }, text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(attempts.size) { index ->
                    val attempt = attempts[index]
                    Text("${attempt.operation} · ${attempt.outcome} · ${attempt.durationMs} ms · ${attempt.owner}/${attempt.transportId}${if (attempt.cacheHit) " · cached" else ""}", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedEngine = null }) { Text("Close") } })
    }
}

@Composable
private fun ResearchOutcomeBar(engine: String, health: dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchEngineRollup) {
    val colors = MaterialTheme.colorScheme
    val parts = listOf(health.successes to colors.primary, health.empty to colors.tertiary, health.failures to colors.error, health.canceled to colors.outline)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().semantics { contentDescription = "$engine: ${health.successes} useful, ${health.empty} empty, ${health.failures} failed, ${health.canceled} canceled. Canceled and skipped attempts are excluded from the useful-rate denominator." }) {
        parts.filter { it.first > 0 }.forEach { (count, color) ->
            androidx.compose.foundation.Canvas(Modifier.weight(count.toFloat()).then(Modifier.padding(vertical = 4.dp)).then(Modifier.height(12.dp))) { drawRect(color) }
        }
    }
    Text("Useful ${health.successes} · Empty ${health.empty} · Failed ${health.failures} · Canceled ${health.canceled}", style = MaterialTheme.typography.bodySmall)
}
