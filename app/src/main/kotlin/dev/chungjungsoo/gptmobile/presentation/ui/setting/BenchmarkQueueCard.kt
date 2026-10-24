package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueState
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BenchmarkQueueCard(batch: BenchmarkBatch, active: Boolean, onSkip: () -> Unit, onStop: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit, onResults: () -> Unit, onExport: () -> Unit) {
    var details by remember(batch.id) { mutableStateOf(false) }
    val now = rememberLiveClock(active)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (active) "Profile ${(batch.activeOrdinal ?: 0) + 1} of ${batch.items.size}" else batch.state.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("${batch.handled}/${batch.items.size} handled · ${batch.failed} failed or blocked · ${batch.items.count { it.state == BenchmarkQueueState.SKIPPED }} skipped · ${((batch.endedAt ?: now) - batch.createdAt).coerceAtLeast(0) / 1000}s", style = MaterialTheme.typography.bodySmall)
            batch.items.take(100).forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        when (item.state) {
                            BenchmarkQueueState.COMPLETED -> Icons.Rounded.CheckCircle
                            BenchmarkQueueState.FAILED, BenchmarkQueueState.BLOCKED, BenchmarkQueueState.INTERRUPTED -> Icons.Rounded.ErrorOutline
                            BenchmarkQueueState.SKIPPED -> Icons.Rounded.SkipNext
                            else -> Icons.Rounded.HourglassEmpty
                        },
                        item.state.name.lowercase()
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${item.ordinal + 1}. ${item.profile.name}")
                        Text(item.failure?.code?.lowercase()?.replace('_', ' ') ?: item.state.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${item.completedCases} cases", style = MaterialTheme.typography.labelSmall)
                }
            }
            batch.storageWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onSkip, enabled = batch.activeOrdinal != null) { Text("Skip profile") }
                    OutlinedButton(onClick = onStop) { Text("Stop all") }
                }
            } else {
                if (batch.canResume) FilledTonalButton(onClick = onResume) { Text("Resume remaining (${batch.pending.size})") }
                if (batch.failed > 0) OutlinedButton(onClick = onRetry) { Text("Retry failed (${batch.failed})") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onResults) { Text("View results") }
                    TextButton(onClick = onExport) { Text("Export") }
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
