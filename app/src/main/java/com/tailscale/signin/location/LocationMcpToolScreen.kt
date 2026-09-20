package com.tailscale.signin.location

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LocationMcpToolScreen() {
    val viewModel: LocationViewModel = hiltViewModel()
    val state by viewModel.locationState.collectAsState()

    val permissionState = rememberMultiplePermissionState(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    LaunchedEffect(Unit) {
        viewModel.requestPermissions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "📍 Location MCP Tool",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Permission Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                when (state.permissionStatus) {
                    PermissionStatus.GRANTED_FINE -> {
                        Text(
                            text = "✅ Fine Location Granted",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    PermissionStatus.GRANTED_COARSE -> {
                        Text(
                            text = "⚠️ Coarse Location Granted",
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    PermissionStatus.DENIED -> {
                        Text(
                            text = "❌ Location Permission Denied",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    PermissionStatus.UNKNOWN -> {
                        Text(
                            text = "⏳ Checking Permissions...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (state.lastKnownLocation != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📍 Last Known Location",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    Text(
                        text = "Updated: ${dateFormat.format(state.lastKnownLocation!!.time)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Latitude: ${state.lastKnownLocation!!.latitude.toString()}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Longitude: ${state.lastKnownLocation!!.longitude.toString()}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Accuracy: ${state.locationAccuracy?.toString()} m",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    state.bearing?.let {
                        Text(
                            text = "Bearing: ${it.toString()}°",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    state.speedMetersPerSecond?.let {
                        Text(
                            text = "Speed: ${it.toString()} m/s",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop Tracking")
                }
            } else {
                Button(
                    onClick = { viewModel.requestLocationUpdates() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Tracking")
                }
            }
        }

        if (state.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Text(
                    text = state.error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
