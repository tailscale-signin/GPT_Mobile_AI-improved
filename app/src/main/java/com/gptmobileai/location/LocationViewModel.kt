package com.gptmobileai.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class LocationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) : ViewModel() {

    // UI State
    private val _locationState = MutableStateFlow(LocationUiState())
    val locationState: StateFlow<LocationUiState> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null

    // Request permissions
    fun requestPermissions() {
        _locationState.value = _locationState.value.copy(
            permissionStatus = PermissionStatus.PENDING
        )
        checkSelfPermission()
    }

    fun hasLocationPermission(): Boolean {
        val hasFineLocation = context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = context.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFineLocation || hasCoarseLocation
    }

    fun checkSelfPermission() {
        _locationState.value = _locationState.value.copy(
            permissionStatus = if (hasLocationPermission()) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
        )
    }

    // Get last known location
    fun getLastKnownLocation() {
        if (!hasLocationPermission()) {
            _locationState.value = _locationState.value.copy(
                permissionStatus = PermissionStatus.DENIED,
                errorMessage = "Location permission not granted"
            )
            return
        }

        viewModelScope.launch {
            _locationState.value = _locationState.value.copy(
                isLoading = true,
                errorMessage = null
            )

            try {
                @Suppress("MissingPermission")
                val location = fusedLocationClient.lastLocation.await()
                if (location != null) {
                    _locationState.value = _locationState.value.copy(
                        lastKnownLocation = LocationData(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitude = location.altitude.toFloat(),
                            accuracy = location.accuracy,
                            timestamp = location.time
                        ),
                        isLoading = false
                    )
                } else {
                    _locationState.value = _locationState.value.copy(
                        errorMessage = "No location available",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _locationState.value = _locationState.value.copy(
                    errorMessage = e.message ?: "Unknown error",
                    isLoading = false
                )
            }
        }
    }

    // Start location updates
    fun requestLocationUpdates(priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
        if (!hasLocationPermission()) {
            _locationState.value = _locationState.value.copy(
                permissionStatus = PermissionStatus.DENIED,
                errorMessage = "Location permission not granted"
            )
            return
        }

        viewModelScope.launch {
            val request = LocationRequest.Builder(
                priority,
                5000L // Update interval in milliseconds
            ).build()
            locationRequest = request

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    super.onLocationResult(result)
                    result.lastLocation?.let { location ->
                        _locationState.value = _locationState.value.copy(
                            lastKnownLocation = LocationData(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                altitude = location.altitude.toFloat(),
                                accuracy = location.accuracy,
                                timestamp = location.time
                            )
                        )
                    }
                }
            }
            locationCallback = callback

            try {
                @Suppress("MissingPermission")
                fusedLocationClient.requestLocationUpdates(
                    request,
                    callback,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                _locationState.value = _locationState.value.copy(
                    errorMessage = e.message ?: "Failed to start location updates"
                )
            }
        }
    }

    // Stop location updates
    fun stopLocationUpdates() {
        viewModelScope.launch {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
            }
            locationCallback = null
            locationRequest = null
        }
    }

    // Cleanup on ViewModel destruction
    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
    }
}

data class LocationUiState(
    val isLoading: Boolean = false,
    val lastKnownLocation: LocationData? = null,
    val permissionStatus: PermissionStatus = PermissionStatus.UNKNOWN,
    val errorMessage: String? = null
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Float = 0f,
    val accuracy: Float = 0f,
    val timestamp: Long = 0L
)

enum class PermissionStatus {
    UNKNOWN, GRANTED, DENIED, PENDING
}
