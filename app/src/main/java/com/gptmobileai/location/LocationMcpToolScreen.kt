package com.gptmobileai.location

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationMcpToolScreen(
    onPermissionGranted: () -> Unit = {},
    viewModel: LocationViewModel = viewModel()
) {
    val state by viewModel.locationState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location MCP Tool") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state.permissionStatus) {
                        PermissionStatus.GRANTED -> MaterialTheme.colorScheme.primaryContainer
                        PermissionStatus.DENIED -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when (state.permissionStatus) {
                            PermissionStatus.GRANTED -> "✅ Location Enabled"
                            PermissionStatus.DENIED -> "❌ Permission Denied"
                            PermissionStatus.PENDING -> "⏳ Requesting Permission"
                            else -> "🔍 Checking Permissions"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Location Data Card
            if (state.lastKnownLocation != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📍 Current Location",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Latitude: ${state.lastKnownLocation?.latitude}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Longitude: ${state.lastKnownLocation?.longitude}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Altitude: ${state.lastKnownLocation?.altitude} m",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Accuracy: ${state.lastKnownLocation?.accuracy} m",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Updated: ${formatTimestamp(state.lastKnownLocation?.timestamp ?: 0L)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📍 No Location Data",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap 'Get Last Known Location' to retrieve your current position",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Error Message
            if (state.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⚠️ Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.getLastKnownLocation() },
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "Loading..." else "Get Last Known Location")
                }

                Button(
                    onClick = { viewModel.requestLocationUpdates() },
                    enabled = state.permissionStatus == PermissionStatus.GRANTED && !state.isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Start Tracking")
                }
            }

            // Stop Button (only show if tracking)
            if (state.permissionStatus == PermissionStatus.GRANTED) {
                OutlinedButton(
                    onClick = { viewModel.stopLocationUpdates() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Tracking")
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
