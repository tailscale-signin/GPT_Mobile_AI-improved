package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsageStatisticsScreen(onBack: () -> Unit, viewModel: UsageStatisticsViewModel = hiltViewModel()) {
    val stats by viewModel.statistics.collectAsStateWithLifecycle()
    val numbers = NumberFormat.getIntegerInstance()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Usage statistics") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to "7 days", 30 to "30 days", 0 to "Stored history").forEach { (days, label) ->
                        FilterChip(selected = stats.days == days, onClick = { viewModel.selectRange(days) }, label = { Text(label) })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GENERATED TOKENS", style = MaterialTheme.typography.labelLarge)
                        Text(numbers.format(stats.generatedTokens), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text("${numbers.format(stats.inputTokens)} input tokens · ${stats.runs} model runs", style = MaterialTheme.typography.bodyMedium)
                        Text("Token usage reported for ${stats.reportedRuns} of ${stats.runs} runs", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                StatisticsCard(if (stats.days == 0) "Generated tokens · last 30 days" else "Generated tokens by day") {
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = MaterialTheme.colorScheme.tertiary
                    val maximum = stats.dailyTokens.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    Canvas(
                        Modifier.fillMaxWidth().height(160.dp).semantics {
                            contentDescription = stats.dailyTokens.joinToString { "${it.first}: ${it.second} tokens" }
                        }
                    ) {
                        val slot = size.width / stats.dailyTokens.size.coerceAtLeast(1)
                        stats.dailyTokens.forEachIndexed { index, (_, value) ->
                            val height = size.height * value.toFloat() / maximum
                            drawRoundRect(Brush.verticalGradient(listOf(primary, secondary)), Offset(slot * index + 2, size.height - height), Size((slot - 4).coerceAtLeast(1f), height), CornerRadius(5f))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stats.dailyTokens.firstOrNull()?.first?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall)
                        Text("Today", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                StatisticsCard("Estimated output · unreported runs") {
                    Text("≈ ${numbers.format(stats.estimatedTokens)} tokens across ${stats.estimatedRuns} completed runs", style = MaterialTheme.typography.titleMedium)
                    Text("Rough text-only estimate: one token per four characters. Excludes reasoning, images, deleted answers and overwritten retries. Estimates are separate from reported totals and charts.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                StatisticsCard("Model performance") {
                    Text("Compare requests, latency and token cost. Includes primary, delegated and synthesis requests.", style = MaterialTheme.typography.bodySmall)
                    if (stats.performance.isEmpty()) Text("Send a message to start tracking performance.")
                    val maximum = stats.performance.maxOfOrNull { it.p95LatencyMs ?: 0 }?.coerceAtLeast(1) ?: 1
                    stats.performance.forEach { row ->
                        val color = modelChartColor(row.model)
                        Text(row.model, color = color, fontWeight = FontWeight.SemiBold)
                        Text("${row.provider} · ${row.requests} requests · ${row.completed} completed", style = MaterialTheme.typography.labelMedium)
                        Text("${numbers.format(row.inputTokens)} input / ${numbers.format(row.outputTokens)} output tokens · ${row.estimatedRequests} estimates", style = MaterialTheme.typography.bodySmall)
                        Text("Latency p50 ${formatLatency(row.medianLatencyMs)} · p95 ${formatLatency(row.p95LatencyMs)}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { (row.p95LatencyMs ?: 0).toFloat() / maximum }, modifier = Modifier.fillMaxWidth().height(8.dp), color = color)
                        Text("First token p50 ${formatLatency(row.medianFirstTokenMs)} · p95 ${formatLatency(row.p95FirstTokenMs)}", style = MaterialTheme.typography.bodySmall)
                        Text(row.outputTokensPerSecond?.let { "%.1f reported output tokens / second".format(it) } ?: "Throughput unavailable until a provider reports tokens", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Latency measures complete model requests. Throughput uses reported output tokens / request duration, including time to first token. p95 uses the nearest-rank percentile. Estimates are not billing totals.", style = MaterialTheme.typography.labelSmall)
                }
            }
            item { RankedUsageChart("Generated tokens by model", stats.modelTokens) }
            item { RankedUsageChart("Generated tokens by AI profile", stats.profileTokens) }
            item { RankedUsageChart("Model usage · runs", stats.modelRuns) }
            item {
                StatisticsCard("Most-used tools") {
                    val maximum = stats.toolUsage.firstOrNull()?.calls?.coerceAtLeast(1) ?: 1
                    if (stats.toolUsage.isEmpty()) Text("No tool calls in this period")
                    stats.toolUsage.take(12).forEach { tool ->
                        Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${tool.calls} calls · ${tool.failures} failures", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { tool.calls.toFloat() / maximum }, modifier = Modifier.fillMaxWidth())
                    }
                    if (stats.toolUsage.size > 12) Text("Top 12 of ${stats.toolUsage.size} tools")
                }
            }
            item {
                StatisticsCard("Profile, provider & model") {
                    if (stats.profileModelUsage.isEmpty()) Text("No model runs in this period")
                    stats.profileModelUsage.take(12).forEach { usage ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(usage.profileName, fontWeight = FontWeight.SemiBold)
                            Text("${usage.provider} · ${usage.model}", style = MaterialTheme.typography.bodyMedium)
                            Text("${usage.runs} runs · ${numbers.format(usage.reportedTokens)} reported output tokens", style = MaterialTheme.typography.labelMedium)
                            if (usage.estimatedTokens > 0) Text("≈ ${numbers.format(usage.estimatedTokens)} additional estimated tokens", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (stats.profileModelUsage.size > 12) Text("Top 12 of ${stats.profileModelUsage.size} combinations")
                }
            }
            item {
                StatisticsCard("Activity & reliability") {
                    listOf(
                        "Run success" to (stats.successPercent?.let { "%.1f%%".format(it) } ?: "—"),
                        "Conversations" to stats.conversations.toString(),
                        "AI profiles used" to stats.profiles.toString(),
                        "Completed runs" to stats.completed.toString(),
                        "Failed / interrupted" to stats.failed.toString(),
                        "Canceled" to stats.canceled.toString(),
                        "Average run duration" to (stats.averageSeconds?.let { "%.1f s".format(it) } ?: "—"),
                        "Tool calls" to stats.toolCalls.toString(),
                        "Failed tool calls" to stats.failedTools.toString()
                    ).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(value, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Text("Based on the latest 10,000 stored runs and tool calls. Success is completed ÷ (completed + failed + interrupted); canceled and active runs are excluded. Providers that omit usage are excluded from token totals. Run duration includes tools and network time.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RankedUsageChart(title: String, values: List<Pair<String, Long>>) {
    StatisticsCard(title) {
        val maximum = values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        if (values.isEmpty()) Text("No reported usage yet", style = MaterialTheme.typography.bodyMedium)
        values.take(12).forEach { (name, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(NumberFormat.getIntegerInstance().format(value), style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { value.toFloat() / maximum }, color = modelChartColor(name), modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        if (values.size > 12) Text("Top 12 of ${values.size}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatisticsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun modelChartColor(name: String): Color {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, Color(0xFF16A6A1), Color(0xFFE99538), Color(0xFFAC7DEC), Color(0xFFE2739C))
    return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
}

private fun formatLatency(value: Long?): String = value?.let { "%.2f s".format(it / 1000.0) } ?: "—"
