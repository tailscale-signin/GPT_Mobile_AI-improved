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
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController

/**
 * Activity for the debug mode UI
 */
class DebugModeActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            DebugModeUI(context = this)
        }
    }
}

@Composable
fun DebugModeScreen(
    debugModeManager: DebugModeManager = DebugModeManager(this)
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
                        onCheckedChange = { 
                            if (isEnabled) {
                                debugModeManager.disableDebugMode()
                            } else {
                                debugModeManager.enableDebugMode()
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
        ) {
            // Debug mode UI components would go here
            Text(
                text = "AETHERION Debug Mode",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Debug mode is ${if (isEnabled) "enabled" else "disabled"}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}