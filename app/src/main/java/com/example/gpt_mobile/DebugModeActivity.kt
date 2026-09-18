package com.example.gpt_mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Activity for the debug mode UI
 */
class DebugModeActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            DebugModeScreen()
        }
    }
}

@Composable
fun DebugModeScreen(
    debugModeManager: DebugModeManager = viewModel()
) {
    val isEnabled by debugModeManager.debugModeEnabled.collectAsState()
    val privacySettings by debugModeManager.privacySettings.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AETHERION Debug Mode") },
                actions = {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { debugModeManager.toggleDebugMode() }
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Debug mode toggle
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
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
                }
            }
            
            // Privacy settings
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Privacy Settings",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    // Add privacy settings UI components here
                }
            }
            
            // Telemetry data display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Telemetry Data",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    // Add telemetry data display here
                }
            }
        }
    }
}