package com.example.gpt_mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Fragment for the debug mode UI
 */
class DebugModeFragment : Fragment() {
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                DebugModeUI(context = requireContext())
            }
        }
    }
}

@Composable
fun DebugModeFragmentContent(
    debugModeManager: DebugModeManager = DebugModeManager(LocalContext.current)
) {
    val isEnabled by debugModeManager.debugModeEnabled.collectAsState()
    
    Scaffold(
        topBar = {
            // TopAppBar implementation would go here
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Debug mode UI components would go here
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
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
    }
}
