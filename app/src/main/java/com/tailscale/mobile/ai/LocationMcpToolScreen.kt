package com.tailscale.mobile.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.Priority

@Composable
fun LocationMcpToolScreen(
    onNavigateBack: () -> Unit,
    viewModel: LocationViewModel = viewModel()
) {
    val state by viewModel.locationState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location MCP Tool") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
            // Permission Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state.permissionStatus) {
                        LocationViewModel.PermissionStatus.GRANTED -> MaterialTheme.colorScheme.primaryContainer
                        LocationViewModel.PermissionStatus.DENIED -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    padding = 16.dp
                ) {
                    Column {
                        Text(
                            text = "Permission Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when (state.permissionStatus) {
                                LocationViewModel.PermissionStatus.GRANTED -> "Granted ✓"
                                LocationViewModel.PermissionStatus.DENIED -> "Denied ✗"
                                else -> "Unknown"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (state.permissionStatus) {
                                LocationViewModel.PermissionStatus.GRANTED -> MaterialTheme.colorScheme.onPrimaryContainer
                                LocationViewModel.PermissionStatus.DENIED -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            // Last Known Location
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Last Known Location",
                        style = MaterialTheme.typography.titleMedium
                    )

                    state.lastKnownLocation?.let { location ->
                        LocationInfoRow("Latitude", formatCoordinate(location.latitude))
                        LocationInfoRow("Longitude", formatCoordinate(location.longitude))
                        LocationInfoRow("Altitude", "${location.altitude} m")
                        LocationInfoRow("Accuracy", "${location.accuracy} m")
                        LocationInfoRow("Timestamp", location.time.toString())
                    } ?: run {
                        Text(
                            text = "No location data available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Error Message
            if (state.error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = state.error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Tracking Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Tracking Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (state.isTracking) "Active 🟢" else "Inactive ⚪",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "Priority: ${state.priority.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.getLastKnownLocation() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Get Last Location")
                }

                if (state.isTracking) {
                    Button(
                        onClick = { viewModel.stopLocationUpdates() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Tracking")
                    }
                } else {
                    Button(
                        onClick = { viewModel.requestLocationUpdates() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Tracking")
                    }
                }
            }
        }
    }
}

@Composable
fun LocationInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatCoordinate(value: Double): String {
    return String.format("%.6f", value)
}
