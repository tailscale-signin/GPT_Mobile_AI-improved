package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Navigation into existing operational owners; opening Activity starts no inference or polling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityScreen(onBack: () -> Unit, onRuns: () -> Unit, onUsage: () -> Unit, onBenchmarks: () -> Unit, onDiagnostics: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Activity") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        })
    }) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ActivityDestination("Runs", "Tasks, saved context and evidence", onRuns) }
            item { ActivityDestination("Usage", "Tokens, costs and response timings", onUsage) }
            item { ActivityDestination("Benchmarks", "Compare profiles and inspect past results", onBenchmarks) }
            item { ActivityDestination("Diagnostics", "Live activity, logs and connection health", onDiagnostics) }
        }
    }
}

@Composable
private fun ActivityDestination(title: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
