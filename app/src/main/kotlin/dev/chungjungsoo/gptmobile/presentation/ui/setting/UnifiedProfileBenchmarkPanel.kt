package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import dev.chungjungsoo.gptmobile.data.benchmark.UnifiedBenchmarkCategory
import dev.chungjungsoo.gptmobile.data.benchmark.UnifiedProfileBenchmarkSuite
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite
import dev.chungjungsoo.gptmobile.data.benchmark.unifiedProfileBenchmarkScore
import java.util.Locale

@Composable
internal fun UnifiedProfileBenchmarkPanel(
    run: BenchmarkRun?,
    previous: BenchmarkRun? = null,
    enabled: Boolean = false,
    onRun: (() -> Unit)? = null,
    onExport: () -> Unit,
    exportEnabled: Boolean,
    currentConfigKey: String? = null,
    configurationIssue: String? = null
) {
    val result = remember(run) { run?.let(::unifiedProfileBenchmarkScore) }
    val earlier = remember(previous, run) {
        previous?.takeIf {
            run != null &&
                it.configKey == run.configKey &&
                it.executionPolicy == run.executionPolicy &&
                it.suiteVersion == run.suiteVersion &&
                it.fixtureRotationSeed == run.fixtureRotationSeed
        }?.let(::unifiedProfileBenchmarkScore)?.takeIf { it.comparable }
    }
    fun score(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (onRun != null) "Standard benchmark" else run?.profileName.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            onRun?.let { start ->
                Text("${benchmarkSuite(BenchmarkMode.STANDARD).size} cases covering reasoning, tools, research, GitHub engineering and recovery. Uses the selected profile and saves progress automatically.")
                Button(onClick = start, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Run Benchmark") }
                configurationIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("Standard measures the listed capabilities and one short delivery trial. Full uses a separate suite with repeated short, medium and long timing trials for performance comparisons.", style = MaterialTheme.typography.bodySmall)
                Text("Provider usage is billed normally. GitHub tests use disposable in-memory fixtures.", style = MaterialTheme.typography.bodySmall)
            }
            run?.let {
                Text("${it.provider} · ${it.model} · ${benchmarkResultDate(it.startedAt)}", style = MaterialTheme.typography.bodySmall)
                BenchmarkPreparationDetails(it)
                if (it.cancellationOrigin == BenchmarkStopOrigin.USER_STOP) Text("Stopped by you. Completed evidence is retained; unstarted cases are not failed answers.", style = MaterialTheme.typography.bodySmall)
                if (currentConfigKey != null && it.configKey != currentConfigKey) {
                    Text("Saved result from an earlier profile configuration. Run again to evaluate the current settings.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Overall intelligence", style = MaterialTheme.typography.titleMedium)
            Text(result?.overall?.let { "${score(it)} / 100" } ?: "Not fully evaluated", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            result?.let {
                Text("${it.categories.count { category -> category.complete }} of 9 categories complete · ${it.evaluated}/${it.planned} observations · ${score(it.coveragePercent)}% coverage", style = MaterialTheme.typography.bodySmall)
                if (!it.comparable) Text("An overall score is available when every required observation is evaluated. Partial results cannot be ranked against complete runs.", style = MaterialTheme.typography.bodySmall)
                if (it.comparable && earlier?.overall != null && it.overall != null) {
                    val delta = it.overall - earlier.overall
                    Text("${if (delta >= 0) "+" else ""}${score(delta)} points against the previous matching run", style = MaterialTheme.typography.bodySmall)
                }
            }
            UnifiedBenchmarkCategory.entries.forEach { category ->
                val observation = result?.categories?.singleOrNull { it.category == category }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(observation?.score?.let { "${score(it)}/100" } ?: "Not evaluated", style = MaterialTheme.typography.labelLarge)
                    }
                    observation?.score?.let { value -> LinearProgressIndicator(progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                    Text("Weight ${category.weight}%" + (observation?.let { " · ${it.evaluated}/${it.planned} evaluated${if (!it.complete && it.evaluated > 0) " · partial" else ""}" } ?: ""), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(UnifiedProfileBenchmarkSuite.LIMITATION, style = MaterialTheme.typography.bodySmall)
            Text("Speed contributes 5%. It requires a correct completed answer and uses fixed 1-second first-text and 20-second total-time anchors. Cost, tokens, retries and original receipts remain in the export.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onExport, enabled = exportEnabled && run != null) { Text("Export results and evidence") }
        }
    }
}
