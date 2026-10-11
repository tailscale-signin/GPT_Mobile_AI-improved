package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkComparisonMetric
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkComparisonMetrics
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlin.math.abs

private fun BenchmarkScoreRow.selectionKey(): String = "$profileUid|$revision|$cohort"

@Composable
internal fun BenchmarkSideBySide(rows: List<BenchmarkScoreRow>, profiles: List<PlatformV2>, retests: List<dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRetest> = emptyList()) {
    var firstKey by rememberSaveable { mutableStateOf<String?>(null) }
    var secondKey by rememberSaveable { mutableStateOf<String?>(null) }
    val first = rows.firstOrNull { it.selectionKey() == firstKey } ?: rows.firstOrNull()
    val candidates = rows.filter { it.selectionKey() != first?.selectionKey() }
    val second = candidates.firstOrNull { it.selectionKey() == secondKey }
        ?: candidates.firstOrNull { it.cohort == first?.cohort } ?: candidates.firstOrNull()
    fun name(row: BenchmarkScoreRow) = profiles.firstOrNull { it.uid == row.profileUid }?.name ?: "Retained model"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CompareArrows, null, tint = MaterialTheme.colorScheme.primary)
            Text("Head-to-head", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SettingsHelpIcon("Model 1 is the baseline. Difference = Model 2 minus Model 1, in percentage points for scores. A lower first-response time is better. Only matching mode, suite and execution cohorts receive differences. Missing or unsupported measurements stay blank.")
        }
        if (first == null || second == null) {
            Text("Benchmark two models using the same plan to unlock side-by-side charts.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ComparisonPicker("1 · Baseline", first, rows, ::name) { row ->
                    if (row.selectionKey() == second.selectionKey()) secondKey = first.selectionKey()
                    firstKey = row.selectionKey()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compare against", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = {
                        firstKey = second.selectionKey()
                        secondKey = first.selectionKey()
                    }) { Icon(Icons.Rounded.SwapHoriz, "Swap baseline and comparison") }
                }
                ComparisonPicker("2 · Challenger", second, candidates, ::name) { secondKey = it.selectionKey() }
            }
            val metrics = benchmarkComparisonMetrics(first, second)
            val priorFirst = retests.firstOrNull { it.current.selectionKey() == first.selectionKey() }?.previous
            val priorSecond = retests.firstOrNull { it.current.selectionKey() == second.selectionKey() }?.previous
            val firstChanges = priorFirst?.let { benchmarkComparisonMetrics(first, it).associate { metric -> metric.label to metric.second } }.orEmpty()
            val secondChanges = priorSecond?.let { benchmarkComparisonMetrics(second, it).associate { metric -> metric.label to metric.second } }.orEmpty()
            if (priorFirst != null || priorSecond != null) Text("Retest bars · theme: retained points · red: lost · yellow: gained. Deltas in the table compare model 2 to model 1.", style = MaterialTheme.typography.bodySmall)
            if (first.cohort != second.cohort) {
                Text("Different test conditions. Charts show each model's own scale; score differences require a matching suite and execution cohort.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Two charts · scores / 100", style = MaterialTheme.typography.titleMedium)
            // Keep both charts side by side, with a readable minimum width on phones.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val viewportWidth = maxWidth
                val chartWidth = ((viewportWidth - 12.dp) / 2).coerceAtLeast(152.dp)
                val scroll = rememberScrollState()
                Column {
                    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ComparisonChart("1", name(first), metrics, true, Modifier.width(chartWidth), firstChanges)
                        ComparisonChart("2", name(second), metrics, false, Modifier.width(chartWidth), secondChanges)
                    }
                    if (viewportWidth < chartWidth * 2 + 12.dp) Text("Swipe horizontally to see both charts", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("Numbered results", style = MaterialTheme.typography.titleMedium)
            Text("Δ = model 2 − model 1 · scores use percentage points", style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Column(Modifier.horizontalScroll(rememberScrollState()).width(maxWidth.coerceAtLeast(340.dp))) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)) {
                        Text("#  Metric", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                        Text("1 · Base", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("2 · Model", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("Δ vs 1", Modifier.weight(1.3f), fontWeight = FontWeight.Bold)
                    }
                    metrics.forEachIndexed { index, metric ->
                        val delta = metric.difference
                        val favorable = delta != null && (if (metric.higherIsBetter) delta > 0 else delta < 0)
                        Row(Modifier.fillMaxWidth().background(if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${metric.label}", Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                            Text(metric.first?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(metric.second?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(
                                delta?.let { "${if (it > 0) "+" else ""}%.1f ${metric.unit}".format(it) } ?: "—",
                                Modifier.weight(1.3f),
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    delta == null || abs(delta) < .05 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    favorable -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }
            Text("No score is invented for unsupported tools or incomplete timing. Quick covers each category once; use Full for repeated timing and the full capability screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComparisonPicker(label: String, selected: BenchmarkScoreRow, rows: List<BenchmarkScoreRow>, name: (BenchmarkScoreRow) -> String, onSelect: (BenchmarkScoreRow) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(if (selected.cohort.contains("device:")) Icons.Rounded.Memory else Icons.Rounded.Cloud, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(name(selected), fontWeight = FontWeight.SemiBold)
                Text("${selected.cohort.substringBefore('|')} · suite ${selected.cohort.split('|').getOrNull(1)} · ${selected.cohort.substringAfterLast(':')}", style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Rounded.ExpandMore, "Choose model")
        }
        DropdownMenu(expanded, { expanded = false }) {
            rows.forEach { row ->
                DropdownMenuItem(text = {
                    Column {
                        Text(name(row))
                        Text("Suite ${row.cohort.split('|').getOrNull(1)} · ${row.cohort.substringAfterLast('|')}", style = MaterialTheme.typography.labelSmall)
                    }
                }, onClick = {
                    onSelect(row)
                    expanded = false
                }, leadingIcon = { Icon(if (row.cohort.contains("device:")) Icons.Rounded.Memory else Icons.Rounded.Cloud, null) })
            }
        }
    }
}

@Composable
private fun ComparisonChart(number: String, name: String, metrics: List<BenchmarkComparisonMetric>, first: Boolean, modifier: Modifier, previous: Map<String, Double?> = emptyMap()) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BarChart, null, tint = MaterialTheme.colorScheme.primary)
                Text("$number · $name", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            metrics.take(10).forEachIndexed { index, metric ->
                BenchmarkRatingBar("${index + 1}. ${metric.label}", if (first) metric.first else metric.second, missing = "—", stacked = true, previous = previous[metric.label])
            }
        }
    }
}
