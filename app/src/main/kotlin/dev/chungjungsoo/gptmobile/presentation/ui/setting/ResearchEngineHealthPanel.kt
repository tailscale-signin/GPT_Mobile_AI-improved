package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.engine.ResearchEngineRegistry
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ResearchEngineHealthPanel(
    disabledEngineIds: Set<String> = emptySet(),
    onEngineEnabled: ((String, Boolean) -> Unit)? = null,
    editingEnabled: Boolean = true,
    onOpenSearch: (() -> Unit)? = null,
    onOpenCitationImport: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Text("${ResearchEngineRegistry.entries.count { it.ready }} implemented adapters · ${ResearchEngineRegistry.entries.count { !it.ready }} unavailable integrations", style = MaterialTheme.typography.titleSmall)
    Text("An implemented adapter still needs a successful request. Useful results, readable full text and examined evidence are tracked separately.", style = MaterialTheme.typography.bodySmall)
    onOpenSearch?.let { open -> OutlinedButton(onClick = open) { Text("Search studies to check access") } }
    if (onEngineEnabled != null) {
        Text("Search sources", style = MaterialTheme.typography.titleSmall)
        ResearchEngineRegistry.entries.filter { it.id in ResearchEngineRegistry.searchableIds }.forEach { engine ->
            ResearchFeatureSwitch(engine.name, engine.id !in disabledEngineIds, editingEnabled) { onEngineEnabled(engine.id, it) }
        }
    }
    var pending by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { pending = !pending }) { Text(if (pending) "Hide unimplemented integrations" else "Unavailable integrations (not queued)") }
    if (pending) {
        onOpenCitationImport?.let { open -> OutlinedButton(onClick = open) { Text("Import citations or open Google Scholar") } }
        ResearchEngineRegistry.entries.filterNot { it.ready }.forEach { engine ->
            Text("${engine.name} · ${if (engine.id == "google_scholar") "Browser-assisted" else "Adapter not ready"}", style = MaterialTheme.typography.labelLarge)
            Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
        }
    }
    var initializationRetry by remember(context) { mutableStateOf(0) }
    val initialized by produceState<Result<ResearchTelemetryStore>?>(null, context, initializationRetry) {
        value = try {
            Result.success(withContext(Dispatchers.IO) { ResearchTelemetryStore(File(context.filesDir, "research-telemetry")) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val store = initialized?.getOrNull()
    if (store == null) {
        Text(if (initialized == null) "Loading engine history…" else "Engine history is unavailable. Research settings remain editable.")
        if (initialized?.isFailure == true) OutlinedButton(onClick = { initializationRetry++ }) { Text("Retry") }
        return
    }
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
    OutlinedButton(onClick = { export.launch("research-engine-events.json") }) { Text("Export native engine events") }
    exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    var windowDays by rememberSaveable { mutableIntStateOf(0) }
    var rollups by remember { mutableStateOf(emptyMap<String, dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchEngineRollup>()) }
    var latencies by remember { mutableStateOf(emptyMap<String, Pair<Long?, Long?>>()) }
    var selectedEngine by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(store, windowDays, retry) {
        loading = true
        error = false
        rollups = emptyMap()
        latencies = emptyMap()
        val since = if (windowDays == 0) 0L else System.currentTimeMillis() - windowDays * 86_400_000L
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
        OutlinedButton(onClick = { retry++ }) { Text("Retry") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((days, label) in listOf(0 to "Retained history", 1 to "24 hours", 7 to "7 days", 30 to "30 days")) {
            FilterChip(selected = windowDays == days, onClick = { windowDays = days }, label = { Text(label) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!loading && !error) {
            ResearchEngineRegistry.entries.filter { it.ready }.forEach { engine ->
                val health = rollups[engine.id]
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
                OutlinedButton(onClick = { selectedEngine = engine.id }) { Text("Recent operation receipts") }
                Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Counts are retained observations, not scientific accuracy. HTTP completion, useful parsed results and article appraisal are separate. Historical events pruned before durable receipts were introduced cannot be reconstructed.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear research statistics") }
    }
    if (confirmClear) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear research statistics?") }, text = { Text("Starts a new measurement epoch. Article evidence is retained; older restored receipts will not reappear in totals.") }, confirmButton = {
            OutlinedButton(onClick = {
                confirmClear = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.clearStatistics() }
                        retry++
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = true
                    }
                }
            }) { Text("Clear statistics") }
        }, dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    }
    selectedEngine?.let { engine ->
        // Dispose the previous result when a different engine opens.
        androidx.compose.runtime.key(engine) { ResearchReceiptsDialog(engine, store) { selectedEngine = null } }
    }
}

@Composable
private fun ResearchReceiptsDialog(engine: String, store: ResearchTelemetryStore, onClose: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<ResearchAttempt>>?>(null, store, engine, retry) {
        value = null
        value = try {
            Result.success(withContext(Dispatchers.IO) { store.recent(engine) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val attempts = result?.getOrNull()
    val name = ResearchEngineRegistry.entries.firstOrNull { it.id == engine }?.name ?: engine
    dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
        onDismissRequest = onClose,
        title = { Text("$name · Recent receipts") },
        text = {
            when {
                result == null -> Text("Loading recent requests…")
                attempts == null -> Column {
                    Text("Recent requests could not be loaded.")
                    OutlinedButton(onClick = { retry++ }) { Text("Retry") }
                }
                attempts.isEmpty() -> Text("No requests recorded for this source.")
                else -> androidx.compose.foundation.lazy.LazyColumn {
                    items(attempts.size) { index ->
                        val attempt = attempts[index]
                        Text("${attempt.operation} · ${attempt.outcome} · ${attempt.durationMs} ms · ${attempt.owner}/${attempt.transportId}${if (attempt.cacheHit) " · cached" else ""}", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onClose) { Text("Close") } }
    )
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
