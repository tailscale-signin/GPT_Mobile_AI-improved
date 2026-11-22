package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkTaskEfficiency

@Composable
internal fun BenchmarkTaskEfficiencyDetails(run: BenchmarkRun, expanded: Boolean = false) {
    val efficiency = remember(run) { benchmarkTaskEfficiency(run) }
    if (efficiency.recordedTasks == 0) return
    Text("Successful fixtures: ${efficiency.successfulTasks}/${efficiency.recordedTasks}${if (efficiency.provisional) " · partial run" else ""}", style = MaterialTheme.typography.bodySmall)
    Text("Elapsed effort per successful fixture: ${benchmarkMetricText(efficiency.elapsedMsPerSuccessfulTask?.div(1000), "s", 2)}", style = MaterialTheme.typography.bodySmall)
    Text("Estimated API cost per successful fixture: ${benchmarkApiCostText(efficiency.apiCostEstimateMicrosPerSuccessfulTask, efficiency.currency)}", style = MaterialTheme.typography.bodySmall)
    if (expanded) {
        Text("Includes elapsed time from unsuccessful attempts and retries, including work skipped after it started. Excludes warmups, setup, downloads and cases skipped before dispatch. These are synthetic fixture results; compare the same suite and conditions.", style = MaterialTheme.typography.bodySmall)
        Text("Retry time already included: ${benchmarkMetricText(efficiency.retryMs?.toDouble(), "ms", 0)} · missing timing: ${efficiency.missingTimingTasks} · priced cases: ${efficiency.pricedTasks}/${efficiency.recordedTasks}", style = MaterialTheme.typography.bodySmall)
        Text("Cost uses configured token rates captured for this run, not an invoice. Discounts, cache rates, tools, subscriptions, hardware and energy are excluded. A local API cost of zero does not mean zero energy use.", style = MaterialTheme.typography.bodySmall)
        if (efficiency.costExclusions.isNotEmpty()) Text("Unknown cost: ${efficiency.costExclusions.entries.joinToString { (reason, count) -> "${reason.replace('_', ' ')} ($count)" }}", style = MaterialTheme.typography.bodySmall)
        if (efficiency.costExclusions.keys.any { "price" in it || it == "currency_unknown" }) {
            Text("Set or refresh this profile's rates in Settings → Advanced Settings → New Chat → Pricing & Discovery, then start a new run. Historical results keep their original price evidence.", style = MaterialTheme.typography.bodySmall)
        }
        if (efficiency.successfulTasks == 0) Text("No per-success figure is available until a fixture passes.", style = MaterialTheme.typography.bodySmall)
    }
}
