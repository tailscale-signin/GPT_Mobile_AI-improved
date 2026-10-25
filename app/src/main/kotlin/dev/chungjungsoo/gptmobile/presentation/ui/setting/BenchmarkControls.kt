package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionPolicy
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun BenchmarkNavigation(selected: Int, debugMode: Boolean = false, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(0, "Queue", Icons.Rounded.PlayArrow), Triple(5, "Results", Icons.Rounded.BarChart), Triple(3, "History", Icons.Rounded.History)).forEach { (id, label, icon) -> BenchmarkAction(label, icon, selected == id, { onSelect(id) }) }
        }
        dev.chungjungsoo.gptmobile.presentation.common.DetailsSection {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(2, "Scoreboard", Icons.Rounded.BarChart), Triple(4, "Delegation", Icons.Rounded.Hub), Triple(6, "Export", Icons.Rounded.Science))
                    .let { if (debugMode) it + Triple(7, "Debug", Icons.Rounded.Science) else it }
                    .forEach { (id, label, icon) -> BenchmarkAction(label, icon, selected == id, { onSelect(id) }) }
            }
        }
    }
}

@Composable
internal fun BenchmarkAction(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val content: @Composable () -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 8.dp))
    }
    if (selected) {
        FilledTonalButton(onClick, modifier.semantics { this.selected = true }, enabled = enabled) { content() }
    } else {
        OutlinedButton(onClick, modifier.semantics { this.selected = false }, enabled = enabled) { content() }
    }
}

@Composable
internal fun BenchmarkPlanPicker(mode: BenchmarkMode, enabled: Boolean = true, includeDiagnostics: Boolean = false, includeCpuExperiment: Boolean = false, includePerformance: Boolean = false, onSelect: (BenchmarkMode) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(BenchmarkMode.QUICK, BenchmarkMode.FULL).forEach { option ->
            val active = mode == option
            Card(
                onClick = { onSelect(option) },
                enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().semantics { selected = active },
                colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (option == BenchmarkMode.QUICK) Icons.Rounded.Bolt else Icons.Rounded.Science, null)
                        if (active) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(option.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite(option).size} planned cases", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (option == BenchmarkMode.QUICK) "Every category once" else "Repeated, deeper coverage", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (includePerformance) {
        OutlinedButton(onClick = { onSelect(BenchmarkMode.PERFORMANCE) }, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { selected = mode == BenchmarkMode.PERFORMANCE }) {
            Text("Local performance · ${dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite(BenchmarkMode.PERFORMANCE).size} planned cases")
        }
        if (mode == BenchmarkMode.PERFORMANCE) Text("Measures cold and warm start, prefill, decode, first visible text, sustained throughput and cleanup on this device. Performance and intelligence results remain separate.", style = MaterialTheme.typography.bodySmall)
    }
    if (includeDiagnostics) {
        OutlinedButton(onClick = { onSelect(BenchmarkMode.DIAGNOSTIC) }, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { selected = mode == BenchmarkMode.DIAGNOSTIC }) {
            Text("Capability diagnostics · ${dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite(BenchmarkMode.DIAGNOSTIC).size} offline tests")
        }
        if (mode == BenchmarkMode.DIAGNOSTIC) Text("Individual reasoning, raw and calculator-assisted arithmetic, tool contracts and attached-image reading. Separate from Full timing scores; no live network tools.", style = MaterialTheme.typography.bodySmall)
        if (includeCpuExperiment) {
            OutlinedButton(onClick = { onSelect(BenchmarkMode.CPU_EXPERIMENT) }, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { selected = mode == BenchmarkMode.CPU_EXPERIMENT }) {
                Text("CPU thread comparison · ${dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite(BenchmarkMode.CPU_EXPERIMENT).size} planned cases")
            }
            if (mode == BenchmarkMode.CPU_EXPERIMENT) Text("Explicit on-device experiment: 2, 4, 6 and 8 CPU threads, with repeated cold and cache-hot trials. Results retain measured reuse, thermal state and actual native configuration; ordinary model scores remain separate.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Test extent and request policy are independent choices. */
@Composable
internal fun BenchmarkPolicyPicker(policy: BenchmarkExecutionPolicy, enabled: Boolean = true, onSelect: (BenchmarkExecutionPolicy) -> Unit) {
    Text("Request settings", style = MaterialTheme.typography.titleSmall)
    BenchmarkExecutionPolicy.entries.forEach { option ->
        OutlinedButton(onClick = { onSelect(option) }, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { selected = policy == option }) {
            Text(option.label)
        }
    }
    Text(
        when (policy) {
            BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1 -> "Uses recorded benchmark overrides for comparable capability tests. Saved chat settings stay unchanged."
            BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1 -> "Uses each selected profile's saved chat settings. Results have a separate policy and comparison population."
        },
        style = MaterialTheme.typography.bodySmall
    )
}
