package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkTaskEfficiency

@Composable
internal fun BenchmarkTaskEfficiencyDetails(efficiency: BenchmarkTaskEfficiency, expanded: Boolean = false) {
    if (efficiency.recordedTasks == 0 && efficiency.setupElapsedMs == null && efficiency.allInElapsedMs == null) return
    if (!expanded) {
        efficiency.setupElapsedMs?.let { Text("Preparation time: ${benchmarkMetricText(it.toDouble() / 1000, "s", 2)}", style = MaterialTheme.typography.bodySmall) }
        efficiency.allInElapsedMs?.let { Text("Total recorded run time: ${benchmarkMetricText(it.toDouble() / 1000, "s", 2)}", style = MaterialTheme.typography.bodySmall) }
        if (efficiency.recordedTasks == 0) {
            Text("No fixture attempts are included in the effort calculation. Preparation is not a failed fixture.", style = MaterialTheme.typography.bodySmall)
            return
        }
        Text("Successful fixtures: ${efficiency.successfulTasks}/${efficiency.recordedTasks}${if (efficiency.provisional) " · partial run" else ""}", style = MaterialTheme.typography.bodySmall)
        Text("Elapsed effort per successful fixture: ${benchmarkMetricText(efficiency.elapsedMsPerSuccessfulTask?.div(1000), "s", 2)}", style = MaterialTheme.typography.bodySmall)
        Text("Estimated API cost per successful fixture: ${benchmarkApiCostText(efficiency.apiCostEstimateMicrosPerSuccessfulTask, efficiency.currency)}", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Preparation: ${benchmarkMetricText(efficiency.setupElapsedMs?.toDouble(), "ms", 0)} · full run: ${benchmarkMetricText(efficiency.allInElapsedMs?.toDouble(), "ms", 0)}. Preparation time is separate from fixture attempts.", style = MaterialTheme.typography.bodySmall)
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
