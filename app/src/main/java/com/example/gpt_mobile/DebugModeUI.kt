package com.example.gpt_mobile

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Composable for the debug mode UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugModeUI(
    context: Context,
    debugModeManager: DebugModeManager = DebugModeManager(context)
) {
    val scope = rememberCoroutineScope()
    val isEnabled by debugModeManager.debugModeEnabled.collectAsState()
    val privacySettings by debugModeManager.privacySettings.collectAsState()
    
    // State for UI elements
    var showTelemetry by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showTokenMetrics by remember { mutableStateOf(false) }
    
    // ViewModel for data collection
    val viewModel: DebugModeViewModel = viewModel()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AETHERION Debug Mode") },
                actions = {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { 
                            scope.launch {
                                if (it) {
                                    viewModel.enableDebugMode()
                                } else {
                                    viewModel.disableDebugMode()
                                }
                            }
                        }
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Debug mode status card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Debug Mode Status",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = if (isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                if (isEnabled) {
                                    viewModel.disableDebugMode()
                                } else {
                                    viewModel.enableDebugMode()
                                }
                            }
                        }
                    ) {
                        Text(if (isEnabled) "Disable Debug Mode" else "Enable Debug Mode")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Privacy settings card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Privacy Settings",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    // Add privacy settings UI components here
                    // For now, showing a placeholder
                    Text(
                        text = "Privacy settings would be configured here",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Telemetry data card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Telemetry Data",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTelemetry = !showTelemetry
                            scope.launch {
                                viewModel.collectTelemetry()
                            }
                        }
                    ) {
                        Text("Collect Telemetry Data")
                    }
                    if (showTelemetry) {
                        Text(
                            text = "Telemetry data would be displayed here",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Diagnostics data card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Hardware Diagnostics",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showDiagnostics = !showDiagnostics
                            scope.launch {
                                viewModel.collectDiagnostics()
                            }
                        }
                    ) {
                        Text("Collect Diagnostics Data")
                    }
                    if (showDiagnostics) {
                        Text(
                            text = "Diagnostics data would be displayed here",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Token metrics card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Token Metrics",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTokenMetrics = !showTokenMetrics
                            scope.launch {
                                viewModel.collectTokenMetrics()
                            }
                        }
                    ) {
                        Text("Collect Token Metrics")
                    }
                    if (showTokenMetrics) {
                        Text(
                            text = "Token metrics would be displayed here",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Export data card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Export Data",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.exportTelemetryData()
                            }
                        }
                    ) {
                        Text("Export Telemetry Data")
                    }
                    Text(
                        text = "Exported data would be saved to file or shared",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
