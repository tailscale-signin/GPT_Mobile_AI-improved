package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    var pending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(since, retry) {
        loading = true
        error = false
        try {
            rollups = withContext(Dispatchers.IO) { ResearchEngineRegistry.entries.filter { it.ready }.associate { it.id to store.rollup(it.id, since) } }
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
                androidx.compose.material3.LinearProgressIndicator(progress = { health.successes.toFloat() / health.attempts }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${engine.name}: ${health.successes} useful, ${health.empty} empty, ${health.failures} failed completed requests. Canceled and skipped attempts excluded." })
                Text("Useful rate: ${"%.1f".format(health.successPercent)}% · definition v2 · completed useful + empty + failed", style = MaterialTheme.typography.bodySmall)
            }
            Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { pending = !pending }) { Text(if (pending) "Hide unimplemented integrations" else "Unavailable integrations (not queued)") }
        if (pending) {
            ResearchEngineRegistry.entries.filterNot { it.ready }.forEach { engine ->
                Text("${engine.name} · Adapter not ready", style = MaterialTheme.typography.labelLarge)
                if (health != null && health.attempts > 0) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { health.successes.toFloat() / health.attempts }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${engine.name}: ${health.successes} useful, ${health.empty} empty, ${health.failures} failed completed requests. Canceled and skipped attempts excluded." })
                    Text("Useful rate: ${"%.1f".format(health.successPercent)}% · definition v2 · completed useful + empty + failed", style = MaterialTheme.typography.bodySmall)
                }
                Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
