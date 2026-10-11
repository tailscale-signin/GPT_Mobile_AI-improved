package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CompareArrows
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
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun BenchmarkNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(0, "Run", Icons.Rounded.PlayArrow),
            Triple(2, "Results", Icons.Rounded.BarChart),
            Triple(5, "Compare", Icons.Rounded.CompareArrows),
            Triple(4, "Delegation", Icons.Rounded.Hub),
            Triple(3, "History", Icons.Rounded.History)
        ).forEach { (id, label, icon) -> BenchmarkAction(label, icon, selected == id, { onSelect(id) }) }
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
internal fun BenchmarkPlanPicker(mode: BenchmarkMode, enabled: Boolean = true, onSelect: (BenchmarkMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(BenchmarkMode.QUICK, BenchmarkMode.FULL).forEach { option ->
            val active = mode == option
            Card(
                onClick = { onSelect(option) },
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { selected = active },
                colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (option == BenchmarkMode.QUICK) Icons.Rounded.Bolt else Icons.Rounded.Science, null)
                        if (active) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(option.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (option == BenchmarkMode.QUICK) "5 tests" else "50 tests", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (option == BenchmarkMode.QUICK) "Every category once" else "Repeated, deeper coverage", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
