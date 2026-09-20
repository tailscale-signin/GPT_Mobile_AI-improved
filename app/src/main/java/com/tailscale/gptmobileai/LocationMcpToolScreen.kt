package com.tailscale.gptmobileai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.gptmobileai.LocationViewModel.Companion.LOCATION_PERMISSION_REQUEST_CODE

/**
 * Location MCP Tool UI - Provides location tracking and display.
 */
@Composable
fun LocationMcpToolScreen(
    viewModel: LocationViewModel = viewModel()
) {
    val state by viewModel.locationState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "📍 Location MCP Tool",
            style = MaterialTheme.typography.headlineMedium
        )

        // Permission status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.permissionGranted) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (state.permissionGranted) "✅ Location Permission Granted" else "⚠️ Location Permission Required",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Last known location
        state.lastKnownLocation?.let { location ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📍 Last Known Location",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Latitude: ${location.latitude}")
                    Text(text = "Longitude: ${location.longitude}")
                    Text(text = "Accuracy: ${location.accuracyMeters}m")
                    Text(text = "Altitude: ${location.altitudeMeters}m")
                    Text(text = "Speed: ${location.speedMetersPerSecond} m/s")
                    Text(text = "Bearing: ${location.bearingDegrees}°")
                    Text(
                        text = "Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(location.timestamp))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Error display
        state.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "❌ Error",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    if (state.permissionGranted) {
                        viewModel.requestLocationUpdates()
                    } else {
                        viewModel.requestLocationPermissions()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("🔄 Start Tracking")
            }

            Button(
                onClick = { viewModel.stopLocationUpdates() },
                modifier = Modifier.weight(1f),
                enabled = state.isTracking
            ) {
                Text("⏹️ Stop Tracking")
            }
        }

        // Get last known location button
        Button(
            onClick = { viewModel.getLastKnownLocation() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📍 Get Last Known Location")
        }
    }
}
