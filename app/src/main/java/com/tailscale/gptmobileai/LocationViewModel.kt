package com.tailscale.gptmobileai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.ActivityCompat
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Location MCP Tool - Provides real-time location tracking and access.
 * 
 * This service uses FusedLocationProviderClient for accurate, power-efficient
 * location updates with automatic permission handling.
 */
@HiltViewModel
class LocationViewModel @Inject constructor(
    private val fusedLocationClient: FusedLocationProviderClient,
) : ViewModel() {

    // State management
    private val _locationState = MutableStateFlow(LocationState())
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null

    /**
     * Request location updates with specified priority and interval.
     * 
     * @param priority Location update priority (BALANCED, HIGH_ACCURACY, etc.)
     * @param minUpdateIntervalMillis Minimum time between updates in milliseconds
     */
    fun requestLocationUpdates(
        priority: Priority.PRIORITY = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        minUpdateIntervalMillis: Long = 5000L
    ) {
        viewModelScope.launch {
            // Check permissions
            val hasPermission = ActivityCompat.checkSelfPermission(
                fusedLocationClient.context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                _locationState.update { it.copy(
                    error = "Location permission not granted. Please enable location access.",
                    lastKnownLocation = null
                )}
                return@launch
            }

            // Create location request
            locationRequest = LocationRequest.Builder(
                minUpdateIntervalMillis,
                0L // fastest update interval
            ).setPriority(priority)
                .setWaitForAccurateLocation(true)
                .build()

            // Create callback
            locationCallback = object : LocationCallback() {
                @SuppressLint("MissingPermission")
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation
                    if (location != null) {
                        _locationState.update { it.copy(
                            lastKnownLocation = LocationData(location),
                            error = null,
                            isTracking = true
                        )}
                    }
                }
            }

            // Start location updates
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    fusedLocationClient.context.mainLooper
                )
            } catch (e: SecurityException) {
                _locationState.update { it.copy(
                    error = "Failed to request location updates: ${e.message}",
                    isTracking = false
                )}
            }
        }
    }

    /**
     * Stop active location tracking.
     */
    fun stopLocationUpdates() {
        viewModelScope.launch {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
            }
            _locationState.update { it.copy(
                isTracking = false,
                error = null
            )}
        }
    }

    /**
     * Get the last known location without requesting updates.
     */
    fun getLastKnownLocation(): LocationData? {
        return try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    _locationState.update { it.copy(
                        lastKnownLocation = LocationData(location),
                        error = null
                    )}
                    LocationData(location)
                } else {
                    null
                }
            }.await()
        } catch (e: SecurityException) {
            _locationState.update { it.copy(
                error = "Failed to get last known location: ${e.message}",
                lastKnownLocation = null
            )}
            null
        }
    }

    /**
     * Check if location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            fusedLocationClient.context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request runtime location permissions.
     */
    fun requestLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ActivityCompat.requestPermissions(
            fusedLocationClient.context as? android.app.Activity,
            permissions,
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}

/**
 * Data class representing a location update.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Float,
    val bearingDegrees: Float,
    val speedMetersPerSecond: Float,
    val timestamp: Long
) {
    fun toGeoJson(): String {
        return "${latitude},$longitude"
    }
}

/**
 * State class for LocationViewModel.
 */
data class LocationState(
    val lastKnownLocation: LocationData? = null,
    val isTracking: Boolean = false,
    val error: String? = null,
    val permissionGranted: Boolean = false
)
