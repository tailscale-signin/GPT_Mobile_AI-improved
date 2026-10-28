package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileStatisticsTab(
    profileUid: String,
    profileName: String,
    onQuickBenchmark: () -> Unit,
    onViewAllStatistics: () -> Unit
) {
    val viewModel: UsageStatisticsViewModel = hiltViewModel()
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()
    val benchmarkViewModel: ProfileBenchmarkViewModel = hiltViewModel()
    val benchmarkHistory by benchmarkViewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resetAt by remember(profileUid) { mutableLongStateOf(Long.MAX_VALUE) }
    var resetReady by remember(profileUid) { mutableStateOf(false) }
    LaunchedEffect(context, profileUid) {
        resetAt = withContext(Dispatchers.IO) {
            context.getSharedPreferences("profile_statistics", android.content.Context.MODE_PRIVATE).getLong("reset:$profileUid", 0L)
        }
        resetReady = true
    }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val samples = remember(statistics.profilePerformance, profileUid, resetAt) {
        statistics.profilePerformance
            .filter { it.profileUid == profileUid }
            .flatMap { it.samples }
            .filter { it.startedAt >= resetAt }
            .distinctBy { it.id }
    }
    val metrics = remember(samples) { modelPerformance(samples).firstOrNull() }
    val quickRuns = remember(benchmarkHistory, profileUid, resetAt) {
        benchmarkHistory.filter { it.profileUid == profileUid && it.mode == BenchmarkMode.QUICK && it.startedAt >= resetAt && it.finished && !it.canceled && it.stoppedReason == null }
            .sortedByDescending(BenchmarkRun::startedAt)
    }
    val latestQuick = quickRuns.firstOrNull()
    val quickRating = remember(quickRuns) { quickRuns.take(5).let { runs -> runs.takeIf { it.isNotEmpty() }?.let { benchmarkRating(it, local = it.first().local) } } }
    val quickScoreLabel = quickRating?.let { rating ->
        rating.score?.let { "$it / 100 · ${rating.grade}" }
            ?: "${rating.completed} completed trials · score needs more verified evidence"
    }
    val completed = samples.count { it.status == "COMPLETED" }
    val failed = samples.count { it.status in setOf("FAILED", "INTERRUPTED") }
    val success = (completed + failed).takeIf { it > 0 }?.let { completed * 100 / it }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PROFILE PERFORMANCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(profileName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Live usage and measured response data, aligned with the app-wide statistics ledger.", style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onQuickBenchmark, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Rounded.Speed, contentDescription = null)
                        Text("Quick benchmark", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = onViewAllStatistics, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Rounded.BarChart, contentDescription = null)
                        Text("App statistics", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Performance scorecard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${samples.size} measured requests · ${statistics.daysLabel()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to "7 days", 30 to "30 days", 0 to "History").forEach { (days, label) ->
                        FilterChip(
                            selected = statistics.days == days,
                            onClick = { viewModel.selectRange(days) },
                            label = { Text(label) }
                        )
                    }
                }
                ProfileMetric("Completed", "$completed${success?.let { " · $it% success" }.orEmpty()}")
                ProfileMetric("Output", "${samples.sumOf { it.outputTokens.coerceAtLeast(0).toLong() }} tokens")
                ProfileMetric("Median response", metrics?.medianLatencyMs?.let(::formatProfileLatency) ?: "Not measured yet")
                ProfileMetric("Time to first token", metrics?.medianFirstTokenMs?.let(::formatProfileLatency) ?: "Not measured yet")
                ProfileMetric("Throughput", metrics?.outputTokensPerSecond?.let { "%.1f tokens/s".format(it) } ?: "Not measured yet")
                if (samples.any { it.estimated }) Text("Estimated trials are retained in the app ledger and excluded from measured throughput.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quick benchmark", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (latestQuick == null) {
                    Text("No completed quick benchmark is saved for this profile yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${latestQuick.profileName} · ${latestQuick.model}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        quickScoreLabel ?: "Score not available",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("${latestQuick.samples.count { it.completed }} completed of ${latestQuick.samples.size} recorded trials", style = MaterialTheme.typography.labelMedium)
                }
                Text("Scores are versioned app measurements, not general intelligence ratings.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { confirmReset = true }, enabled = resetReady && samples.isNotEmpty()) { Text("Reset this profile's view") }
        }
        Text("Resetting this view keeps the shared app statistics and backed-up run history intact.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset $profileName statistics view?") },
            text = { Text("This profile tab will start a new measurement period. Existing run history remains available to app-wide statistics and backup.") },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    scope.launch(Dispatchers.IO) {
                        context.getSharedPreferences("profile_statistics", android.content.Context.MODE_PRIVATE)
                            .edit().putLong("reset:$profileUid", now).apply()
                    }
                    resetAt = now
                    confirmReset = false
                }) { Text("Reset view") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ProfileMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun UsageStatistics.daysLabel(): String = when (days) {
    7 -> "last 7 days"
    30 -> "last 30 days"
    else -> "stored history"
}

private fun formatProfileLatency(ms: Long): String = if (ms < 1000) "$ms ms" else "%.2f s".format(ms / 1000.0)
