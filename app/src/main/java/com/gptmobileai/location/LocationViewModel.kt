package com.gptmobileai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val context: Context
) : ViewModel() {

    // UI State
    private val _locationState = MutableStateFlow(LocationUiState())
    val locationState: StateFlow<LocationUiState> = _locationState.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null

    // Request permissions
    fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        _locationState.value = _locationState.value.copy(
            permissionStatus = PermissionStatus.PENDING
        )

        // In real app, call ActivityCompat.requestPermissions here
        // For now, simulate success if permissions already granted
        checkSelfPermission()
    }

    private fun checkSelfPermission() {
        val hasFineLocation = context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = context.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        _locationState.value = _locationState.value.copy(
            permissionStatus = if (hasFineLocation && hasCoarseLocation) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
        )
    }

    // Get last known location
    fun getLastKnownLocation() {
        viewModelScope.launch {
            _locationState.value = _locationState.value.copy(
                isLoading = true,
                errorMessage = null
            )

            try {
                val location = fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            _locationState.value = _locationState.value.copy(
                                lastKnownLocation = LocationData(
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    altitude = location.altitude ?: 0f,
                                    accuracy = location.accuracy ?: 0f,
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
                    }
                    .addOnFailureListener { e ->
                        _locationState.value = _locationState.value.copy(
                            errorMessage = e.message ?: "Unknown error",
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
    fun requestLocationUpdates(priority: Priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
        if (_locationState.value.permissionStatus != PermissionStatus.GRANTED) {
            _locationState.value = _locationState.value.copy(
                errorMessage = "Location permission not granted"
            )
            return
        }

        viewModelScope.launch {
            locationRequest = LocationRequest.Builder(
                priority,
                5000L // Update interval in milliseconds
            ).build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    super.onLocationResult(result)
                    result.lastLocation?.let { location ->
                        _locationState.value = _locationState.value.copy(
                            lastKnownLocation = LocationData(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                altitude = location.altitude ?: 0f,
                                accuracy = location.accuracy ?: 0f,
                                timestamp = location.time
                            )
                        )
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
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
