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
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.engine.ResearchEngineRegistry
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ResearchEngineHealthPanel() {
    val context = LocalContext.current
    val store = remember(context) { ResearchTelemetryStore(File(context.filesDir, "research-telemetry")) }
    var since by remember { mutableStateOf(0L) }
    var rollups by remember { mutableStateOf(emptyMap<String, dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchEngineRollup>()) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(since) {
        rollups = withContext(Dispatchers.IO) { ResearchEngineRegistry.entries.filter { it.ready }.associate { it.id to store.rollup(it.id, since) } }
    }
    Row {
        TextButton(onClick = { since = 0 }) { Text("Lifetime") }
        TextButton(onClick = { since = System.currentTimeMillis() - 86_400_000 }) { Text("24 hours") }
        TextButton(onClick = { since = System.currentTimeMillis() - 7 * 86_400_000L }) { Text("7 days") }
        TextButton(onClick = { since = System.currentTimeMillis() - 30 * 86_400_000L }) { Text("30 days") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ResearchEngineRegistry.entries.filter { it.ready }.forEach { engine ->
            val health = rollups[engine.id]
            Text(engine.name + " · " + engine.capability, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
            Text(
                if (health == null || health.attempts == 0) {
                    "Unknown · No requests yet"
                } else {
                    "${health.successes}/${health.attempts} successful HTTP attempts · ${health.failures} failures${if (health.lowSample) " · Low sample" else ""}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { pending = !pending }) { Text(if (pending) "Hide pending engines" else "Excluded & pending engines") }
        if (pending) {
            ResearchEngineRegistry.entries.filterNot { it.ready }.forEach { engine ->
                Text("${engine.name} · Pending", style = MaterialTheme.typography.labelLarge)
                Text(engine.limitation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
