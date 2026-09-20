package com.tailscale.gptmobileai.location.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailscale.gptmobileai.location.LocationState

/**
 * Location MCP Tool UI Component.
 */
@Composable
fun LocationMcpTool(
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val locationState by viewModel.locationState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Location MCP Tool",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        when {
            locationState.error != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = locationState.error!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            !locationState.isLocationAvailable() -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "No location data available",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            else -> {
                LocationDataCard(locationState = locationState)
            }
        }

        // Permission button
        if (!viewModel.hasLocationPermission()) {
            Button(
                onClick = { viewModel.requestLocationPermission() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request Location Permission")
            }
        } else {
            Button(
                onClick = { viewModel.requestLastKnownLocation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = locationState.error == null
            ) {
                Text("Get Last Known Location")
            }
        }

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.startLocationUpdates() },
                enabled = locationState.error == null
            ) {
                Text("Start Updates")
            }

            Button(
                onClick = { viewModel.stopLocationUpdates() },
                enabled = locationState.error == null
            ) {
                Text("Stop Updates")
            }
        }
    }
}

@Composable
private fun LocationDataCard(locationState: LocationState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Coordinates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${locationState.latitude}°, ${locationState.longitude}°",
                style = MaterialTheme.typography.bodyLarge
            )

            Text(
                text = "Accuracy: ${locationState.accuracy?.let { "%.2f".format(it) } ?: "N/A"} m",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Timestamp: ${locationState.timestamp?.let { java.util.Date(it) }}",
                style = MaterialTheme.typography.bodyMedium
            )

            locationState.speed?.let { speed ->
                Text(
                    text = "Speed: ${speed} m/s",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            locationState.bearing?.let { bearing ->
                Text(
                    text = "Bearing: ${bearing}°",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            locationState.altitude?.let { altitude ->
                Text(
                    text = "Altitude: ${altitude} m",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
