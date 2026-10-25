package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkComparisonRow

@Composable
internal fun BenchmarkBackendComparison(rows: List<BenchmarkComparisonRow>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CPU / GPU / NPU comparison", style = MaterialTheme.typography.titleMedium)
            Text("Each row uses one observed artifact and comparison context. Unmeasured backends and incompatible artifacts stay separate.", style = MaterialTheme.typography.bodySmall)
            BenchmarkEvidenceDisclosure("Backend comparison evidence", rows.joinToString { it.id }) {
                if (rows.isEmpty()) Text("No native comparison measurements are available.")
                var visible by remember(rows) { mutableIntStateOf(8) }
                rows.take(visible).forEach { row ->
                    Text("${row.dimension.label} · ${row.executionPolicy?.label ?: "Unknown request policy"}", style = MaterialTheme.typography.titleSmall)
                    Text("Artifact ${row.artifactDigest ?: "Unknown"}\nFamily ${row.modelFamily ?: "Unknown"} · quantization ${row.quantization ?: "Unknown"} · context ${row.contextTokens ?: "Unknown"}\nFixture ${row.fixtureDigest ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.cells.forEach { cell ->
                            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${cell.backend.uppercase()} · ${cell.status.name.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.titleSmall)
                                Text("Raw run-median: ${benchmarkMetricText(cell.raw.median, row.unit)} · ${cell.raw.count} recorded run medians", style = MaterialTheme.typography.bodySmall)
                                Text("Qualified run-median: ${benchmarkMetricText(cell.qualified.median, row.unit)} · ${cell.qualified.count} qualified run medians", style = MaterialTheme.typography.bodySmall)
                                Text("${cell.qualifiedTrialCount} underlying qualified trials · ${cell.aggregation}", style = MaterialTheme.typography.labelSmall)
                                Text("Dimension score: ${cell.dimensionScore?.let { benchmarkMetricText(it, "/ 100", 1) } ?: "Unranked"}", style = MaterialTheme.typography.bodySmall)
                                Text("Comparable: ${if (cell.comparable) "Repeated matching observations" else "Not established"}", style = MaterialTheme.typography.labelSmall)
                                Text("Runtime: ${cell.runtimeVersions.joinToString().ifBlank { "Unknown" }}\nRuns: ${cell.sourceRunIds.joinToString().ifBlank { "Not recorded" }}", style = MaterialTheme.typography.labelSmall)
                                if (cell.reasons.isNotEmpty()) Text(cell.reasons.joinToString("\n"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(row.explanation, style = MaterialTheme.typography.labelSmall)
                }
                if (visible < rows.size) TextButton(onClick = { visible += 8 }) { Text("Show more comparison contexts (${visible.coerceAtMost(rows.size)}/${rows.size})") }
            }
        }
    }
}
