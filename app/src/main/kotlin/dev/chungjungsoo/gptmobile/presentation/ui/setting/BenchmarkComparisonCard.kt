package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow
import dev.chungjungsoo.gptmobile.data.benchmark.IntelligenceBenchmark
import dev.chungjungsoo.gptmobile.data.benchmark.IntelligenceScore
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkScoreDimensions
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite

@Composable
internal fun BenchmarkComparisonCard(name: String, model: String, row: BenchmarkScoreRow, onClick: () -> Unit) {
    val tint = benchmarkTint(row.cohort.contains("device:"))
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.overall?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.headlineLarge, color = tint, fontWeight = FontWeight.Bold)
                    Text("RATING / 100", style = MaterialTheme.typography.labelSmall)
                }
            }
            BenchmarkRatingBar("Overall", row.overall)
            BenchmarkRatingBar("Intelligence · text capability", row.intelligence?.score, missing = if (row.cohort.startsWith("QUICK|")) "Not in Quick Check" else "${row.intelligence?.answered ?: 0} / 36 answered")
            Text("Intelligence is independent of speed and app rating. Open this profile for domain results and coverage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            benchmarkScoreDimensions(row, row.cohort.contains("device:")).forEach { dimension ->
                BenchmarkRatingBar(dimension.label, dimension.score, missing = benchmarkMissingScoreLabel(row, dimension.label))
            }
            Text("Accuracy details", style = MaterialTheme.typography.titleSmall)
            listOf("task" to "Task accuracy", "json" to "Structured output", "tools" to "Tool success").forEach { (category, label) ->
                val score = row.accuracyByCategory[category]
                if (score != null) {
                    BenchmarkRatingBar(label, score)
                } else {
                    MetricLine(
                        label,
                        when {
                            category in row.unsupportedCategories -> "Unsupported"
                            category == "tools" && row.cohort.startsWith("FULL|") -> "Not in Core Text"
                            else -> "Not measured"
                        }
                    )
                }
            }
            Text("${if (row.verified) "Verified timing" else "Provisional timing"} · ${row.sampleCount} measured trials", style = MaterialTheme.typography.labelMedium, color = tint)
            MetricLine("Generation", row.speed?.let { "%.1f ref tok/s".format(it) } ?: "Not measured")
            MetricLine("First response", row.firstTextMs?.let { "%.0f ms".format(it) } ?: "Not measured")
            Text(row.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BenchmarkRatingBar(label: String, value: Double?, missing: String = "Not measured") {
    val fraction by animateFloatAsState(((value ?: 0.0) / 100).toFloat().coerceIn(0f, 1f), label = label)
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.semantics { contentDescription = "$label: ${value?.let { "%.1f out of 100".format(it) } ?: missing}" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value?.let { "%.1f / 100".format(it) } ?: missing, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest)) {
            if (value != null) Box(Modifier.fillMaxWidth(fraction).height(9.dp).background(Brush.horizontalGradient(listOf(colors.primary, colors.tertiary))))
        }
    }
}

internal fun benchmarkMissingScoreLabel(row: BenchmarkScoreRow?, dimension: String): String = when {
    row == null -> "Run a benchmark"
    dimension == "Speed" && row.speed != null -> "Needs verified baseline"
    dimension == "First response" && row.firstTextMs != null -> "Needs verified baseline"
    dimension == "Consistency" -> "Needs repeated timing"
    else -> "Incomplete coverage"
}

@Composable
internal fun IntelligenceResults(result: IntelligenceScore?, quick: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BenchmarkRatingBar("Intelligence", result?.score, missing = if (quick) "Not in Quick Check" else "Incomplete coverage")
        Text(if (quick) "Run Core Text or Agent Standard to measure Intelligence." else "${result?.answered ?: 0} / ${IntelligenceBenchmark.suite.size} answered · fixed text capability screen v${IntelligenceBenchmark.VERSION}", style = MaterialTheme.typography.labelMedium)
        if (!quick && result == null) Text("Run the updated Standard suite; older saved runs do not contain Intelligence tests.", style = MaterialTheme.typography.bodySmall)
        IntelligenceBenchmark.suite.map { it.workload }.distinct().forEach { domain ->
            MetricLine(domain, result?.domains?.get(domain)?.let { "%.1f / 100".format(it) } ?: "Requires 6 answers")
        }
        Text("Equal average of six domain accuracies. Infrastructure failures leave coverage incomplete; speed, cost and missing tools do not change Intelligence.", style = MaterialTheme.typography.bodySmall)
        Text(IntelligenceBenchmark.LIMITATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun BenchmarkCoverage(run: BenchmarkRun?, mode: BenchmarkMode) {
    val suite = benchmarkSuite(mode, run?.fixtureContextTokens)
    val samples = run?.samples.orEmpty().associateBy { it.testId }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Test coverage", style = MaterialTheme.typography.titleMedium)
        Text("${samples.size} / ${suite.size} outcomes recorded. Every planned category stays visible.", style = MaterialTheme.typography.bodySmall)
        run?.fixtureContextTokens?.let { Text("Context tier: $it tokens · padding scaled to fit this model", style = MaterialTheme.typography.labelSmall) }
        suite.groupBy { if (it.category == "speed") "Speed · ${it.workload}" else it.workload.takeUnless { key -> key == "short" } ?: it.category }.forEach { (label, cases) ->
            val results = cases.mapNotNull { samples[it.id] }
            val passed = results.count { it.outcome == BenchmarkOutcome.PASSED }
            val unavailable = results.count { it.outcome == BenchmarkOutcome.UNSUPPORTED }
            val skipped = results.count { it.outcome in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.CANCELED) }
            val failed = results.size - passed - unavailable - skipped
            val pending = cases.size - results.size
            Column {
                MetricLine(label.replaceFirstChar { it.uppercase() }, "${results.size} / ${cases.size}")
                Text(
                    buildList {
                        if (passed > 0) add("$passed passed")
                        if (failed > 0) add("$failed failed")
                        if (unavailable > 0) add("$unavailable unsupported")
                        if (skipped > 0) add("$skipped skipped")
                        if (pending > 0) add("$pending ${if (run?.finished == true) "not run" else "pending"}")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
