package com.tailscale.gptmobileai.location

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailscale.gptmobileai.location.LocationViewModel.LocationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationMcpToolScreen(
    viewModel: LocationViewModel = hiltViewModel()
) {
    val state by viewModel.locationState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Location MCP Tool") }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state) {
                is LocationState.PermissionDenied -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEB3B))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Permission Denied", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Please grant location permission to use this feature.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                is LocationState.PermissionGranted -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Permission Granted", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Location access is enabled.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                is LocationState.Loading -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Loading...", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Requesting location updates...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                is LocationState.Success -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Location Found", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Latitude: ${(state.latitude).toString()}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Longitude: ${(state.longitude).toString()}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Accuracy: ${state.accuracy}m",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Updated: ${state.timestamp}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                is LocationState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF44336))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Error", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Failed to retrieve location. Please try again.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
