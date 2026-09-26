package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
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
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            item { RankedUsageChart("Generated tokens by model", stats.modelTokens) }
            item { RankedUsageChart("Generated tokens by AI profile", stats.profileTokens) }
            item { RankedUsageChart("Model usage · runs", stats.modelRuns) }
            item {
                StatisticsCard("Activity & reliability") {
                    listOf(
                        "Conversations" to stats.conversations.toString(),
                        "AI profiles used" to stats.profiles.toString(),
                        "Completed runs" to stats.completed.toString(),
                        "Failed / interrupted" to stats.failed.toString(),
                        "Canceled" to stats.canceled.toString(),
                        "Average run duration" to (stats.averageSeconds?.let { "%.1f s".format(it) } ?: "—"),
                        "Tool calls" to stats.toolCalls.toString(),
                        "Failed tool calls" to stats.failedTools.toString()
                    ).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(value, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Text("Based on the latest 10,000 stored runs and tool calls. Providers that omit usage are excluded from token totals. Run duration includes tools and network time.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            LinearProgressIndicator(progress = { value.toFloat() / maximum }, modifier = Modifier.fillMaxWidth().height(8.dp))
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
