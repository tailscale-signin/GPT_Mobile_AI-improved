package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow

/** One result identity, population and provenance are shared by summaries and details. */
@Composable
internal fun BenchmarkResultCard(row: BenchmarkScoreRow, profile: String) {
    var expanded by remember(row.resultId) { mutableStateOf(false) }
    fun percent(value: Double?) = value?.let { "%.1f%%".format(it) } ?: "Not measured"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(row.backend, row.accelerator).joinToString(" / ").ifBlank { "Remote or unknown backend" }, style = MaterialTheme.typography.labelMedium)
            Text("Latest attempt: ${row.latestAttemptStatus.lowercase().replace('_', ' ')}")
            Text("Answer accuracy ${percent(row.quality)} · delivery ${percent(row.reliability)} · availability ${percent(row.availability)}")
            Text("${if (row.verified) "Verified" else "Provisional"} timing · ${row.speed?.let { "%.1f reference units/s".format(it) } ?: "Not measured"} · ${row.sourceRunIds.size} source runs", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Metric details") }
            if (expanded) {
                Text(row.explanation, style = MaterialTheme.typography.bodySmall)
                row.metrics.forEach { metric ->
                    Text("${metric.id.replace('_', ' ')}: ${metric.value?.let { "%.2f ${metric.unit}".format(it) } ?: "Not measured"}", style = MaterialTheme.typography.titleSmall)
                    Text("${metric.numerator}/${metric.denominator} · ${metric.population} · ${metric.uniqueFixtureCount} unique fixtures · ${metric.runCount} runs · ${metric.aggregation} · ${metric.policyVersion}", style = MaterialTheme.typography.bodySmall)
                    if (metric.exclusions.isNotEmpty()) Text("Excluded: ${metric.exclusions.entries.joinToString { "${it.key.lowercase()} ${it.value}" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Runs: ${metric.sourceRunIds.joinToString()}\nAttempts: ${metric.sourceAttemptIds.joinToString().ifBlank { "Historical identifiers unavailable" }}", style = MaterialTheme.typography.labelSmall)
                }
                Text("Comparison context: ${row.cohort}\nResult: ${row.resultId}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
