package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionPolicy
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import dev.chungjungsoo.gptmobile.data.benchmark.retryCondition
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BenchmarkQueueCard(batch: BenchmarkBatch, active: Boolean, onSkip: () -> Unit, onStop: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit, onResults: () -> Unit, onExport: () -> Unit, runs: List<BenchmarkRun> = emptyList(), onRunWithProfileSettings: () -> Unit = {}) {
    var details by remember(batch.id) { mutableStateOf(false) }
    val now = rememberLiveClock(active)
    val runById = remember(runs) { runs.associateBy { it.id } }
    val stoppedOrdinals = batch.items.filter { runById[it.runId]?.cancellationOrigin == BenchmarkStopOrigin.USER_STOP || it.failure?.code == "USER_STOP" }.map { it.ordinal }.toSet()
    val failed = batch.items.count { it.ordinal !in stoppedOrdinals && it.state in setOf(BenchmarkQueueState.FAILED, BenchmarkQueueState.BLOCKED) }
    val interrupted = batch.items.count { it.ordinal !in stoppedOrdinals && it.state == BenchmarkQueueState.INTERRUPTED }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (active) "Profile ${(batch.activeOrdinal ?: 0) + 1} of ${batch.items.size}" else batch.state.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("${batch.handled}/${batch.items.size} handled · $failed failed or blocked · $interrupted interrupted · ${stoppedOrdinals.size} stopped by you · ${batch.items.count { it.state == BenchmarkQueueState.SKIPPED }} skipped · ${((batch.endedAt ?: now) - batch.createdAt).coerceAtLeast(0) / 1000}s", style = MaterialTheme.typography.bodySmall)
            Text("Request policy: ${batch.executionPolicy?.label ?: "Unknown (historical run)"}", style = MaterialTheme.typography.labelSmall)
            batch.items.take(100).forEach { item ->
                val run = runById[item.runId]
                val stopped = item.ordinal in stoppedOrdinals
                val progress = benchmarkQueueProgress(item, run)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (stopped) {
                            Icons.Rounded.Stop
                        } else {
                            when (item.state) {
                                BenchmarkQueueState.COMPLETED -> Icons.Rounded.CheckCircle
                                BenchmarkQueueState.FAILED, BenchmarkQueueState.BLOCKED, BenchmarkQueueState.INTERRUPTED -> Icons.Rounded.ErrorOutline
                                BenchmarkQueueState.SKIPPED -> Icons.Rounded.SkipNext
                                else -> Icons.Rounded.HourglassEmpty
                            }
                        },
                        if (stopped) "Stopped by you" else item.state.name.lowercase()
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${item.ordinal + 1}. ${item.profile.name}")
                        Text(if (stopped) "Stopped by you" else item.failure?.code?.lowercase()?.replace('_', ' ') ?: item.state.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                        Text("Planned ${progress.planned ?: "unknown"} · executed ${progress.executed ?: "unknown"} · passed ${progress.passed ?: "unknown"} · failed ${progress.failed ?: "unknown"} · skipped ${progress.skipped ?: "unknown"}", style = MaterialTheme.typography.labelSmall)
                        run?.let { BenchmarkPreparationDetails(it) }
                        if (!stopped) item.failure?.let { Text(it.retryCondition(), style = MaterialTheme.typography.labelSmall) }
                        if (item.state == BenchmarkQueueState.RUNNING) {
                            Text(progress.currentCase?.let { "Current test: $it" } ?: "No fixture is currently executing.", style = MaterialTheme.typography.bodySmall)
                            progress.planned?.takeIf { it > 0 }?.let { planned ->
                                LinearProgressIndicator(progress = { (progress.completed.toFloat() / planned).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            batch.storageWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (active) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onSkip, enabled = batch.activeOrdinal != null) { Text("Skip profile") }
                    OutlinedButton(onClick = onStop) { Text("Stop all") }
                }
            } else {
                if (batch.canResume) FilledTonalButton(onClick = onResume) { Text("Resume remaining (${batch.pending.size})") }
                if (batch.failed > 0) OutlinedButton(onClick = onRetry) { Text("Run failed profiles again (${batch.failed})") }
                if (batch.executionPolicy != BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1 && batch.items.any { it.failure?.code?.startsWith("REASONING_") == true }) {
                    TextButton(onClick = onRunWithProfileSettings) { Text("Run with saved profile settings") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onResults) { Text("View results") }
                    TextButton(onClick = onExport) { Text("Export all benchmark data") }
                }
            }
            TextButton(onClick = { details = true }) { Text("Details") }
        }
    }
    if (details) {
        FadingModalBottomSheet(onDismissRequest = { details = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${batch.mode.label} · suite ${batch.suiteVersion}", style = MaterialTheme.typography.titleLarge)
                Text("Order: ${batch.orderPolicy}. Completed profiles are retained. Resume starts untouched profiles; retrying an interrupted profile creates a new linked batch and may submit a new provider request.")
                batch.items.filter { it.failure != null }.take(20).forEach { item ->
                    val failure = checkNotNull(item.failure)
                    Text("${item.profile.name} · ${failure.code}\n${failure.stage} · ${failure.message}", style = MaterialTheme.typography.bodyMedium)
                    failure.nextEligibleAt?.let { Text("Next eligible retry: ${java.time.Instant.ofEpochMilli(it)}") }
                }
            }
        }
    }
}
