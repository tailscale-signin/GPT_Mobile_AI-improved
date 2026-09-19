package com.example.gptmobileai.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.debug.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugModeScreen(
    telemetryCollector: TelemetryCollector,
    tokenMetricsCollector: TokenMetricsCollector,
    toolMetricsCollector: ToolMetricsCollector,
    diagnosticsTelemetryProvider: DiagnosticsTelemetryProvider,
    exportService: ExportService,
    onDismiss: () -> Unit
) {
    var showExportDialog by remember { mutableStateOf(false) }

    val liveState by diagnosticsTelemetryProvider.liveState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔬 DEBUG MODE") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Metrics Panel
            LiveMetricsPanel(tokenMetrics = liveState.tokenMetrics)

            // Hardware Diagnostics Panel
            HardwareDiagnosticsPanel(hardware = liveState.hardwareDiagnostics)

            // Tool Analytics Panel
            ToolAnalyticsPanel(toolMetrics = liveState.toolMetrics)

            // Export Button
            OutlinedButton(
                onClick = { showExportDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📥 Export Diagnostics")
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exportService = exportService,
            onDismiss = { showExportDialog = false }
        )
    }
}
