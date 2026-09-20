package com.tailscale.signin.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
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

data class LocationState(
    val permissionStatus: PermissionStatus = PermissionStatus.UNKNOWN,
    val lastKnownLocation: Location? = null,
    val isTracking: Boolean = false,
    val error: String? = null,
    val locationAccuracy: Float? = null,
    val bearing: Float? = null,
    val speedMetersPerSecond: Float? = null
)

enum class PermissionStatus {
    GRANTED_FINE,
    GRANTED_COARSE,
    DENIED,
    UNKNOWN
}

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val context: Context
) : ViewModel() {

    private val _locationState = MutableStateFlow(LocationState())
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null

    fun requestPermissions() {
        viewModelScope.launch {
            val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            _locationState.value = _locationState.value.copy(
                permissionStatus = if (fineGranted) PermissionStatus.GRANTED_FINE else if (coarseGranted) PermissionStatus.GRANTED_COARSE else PermissionStatus.DENIED
            )
        }
    }

    fun getLastKnownLocation() {
        viewModelScope.launch {
            try {
                val location = fusedLocationClient.lastLocation.await()
                _locationState.value = _locationState.value.copy(
                    lastKnownLocation = location,
                    locationAccuracy = location.accuracy,
                    bearing = location.bearing,
                    speedMetersPerSecond = location.speed
                )
            } catch (e: Exception) {
                _locationState.value = _locationState.value.copy(
                    error = "Failed to get last known location: ${e.message}"
                )
            }
        }
    }

    fun requestLocationUpdates(priority: Priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY, minUpdateIntervalMillis: Long = 5000L) {
        if (_locationState.value.permissionStatus != PermissionStatus.GRANTED_FINE && _locationState.value.permissionStatus != PermissionStatus.GRANTED_COARSE) {
            _locationState.value = _locationState.value.copy(error = "Location permission not granted")
            return
        }

        viewModelScope.launch {
            locationRequest = LocationRequest.Builder(minUpdateIntervalMillis)
                .setPriority(priority)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    super.onLocationResult(result)
                    result.lastLocation?.let { location ->
                        _locationState.value = _locationState.value.copy(
                            lastKnownLocation = location,
                            isTracking = true,
                            locationAccuracy = location.accuracy,
                            bearing = location.bearing,
                            speedMetersPerSecond = location.speed
                        )
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null
            )
        }
    }

    fun stopLocationUpdates() {
        viewModelScope.launch {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                _locationState.value = _locationState.value.copy(isTracking = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
    }
}
