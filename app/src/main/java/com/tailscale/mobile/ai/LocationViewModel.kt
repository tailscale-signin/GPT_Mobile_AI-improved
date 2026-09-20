package com.tailscale.mobile.ai

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

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) : ViewModel() {

    private val _locationState = MutableStateFlow(LocationUiState())
    val locationState: StateFlow<LocationUiState> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null
    private var isTracking = false

    data class LocationUiState(
        val permissionStatus: PermissionStatus = PermissionStatus.UNKNOWN,
        val lastKnownLocation: Location? = null,
        val error: String? = null,
        val isTracking: Boolean = false,
        val priority: Priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
    )

    enum class PermissionStatus {
        GRANTED, DENIED, UNKNOWN
    }

    fun requestPermissions() {
        viewModelScope.launch {
            val permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            _locationState.value = _locationState.value.copy(
                permissionStatus = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
            )
        }
    }

    fun getLastKnownLocation() {
        viewModelScope.launch {
            try {
                val location: Location? = fusedLocationClient.lastLocation.await()
                _locationState.value = _locationState.value.copy(
                    lastKnownLocation = location,
                    error = null
                )
            } catch (e: Exception) {
                _locationState.value = _locationState.value.copy(
                    error = e.message ?: "Failed to get last known location"
                )
            }
        }
    }

    fun requestLocationUpdates(priority: Priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY, minUpdateIntervalMillis: Long = 5000L) {
        if (isTracking) return

        viewModelScope.launch {
            locationRequest = LocationRequest.Builder(
                minUpdateIntervalMillis,
                0 // fastest interval for testing
            ).setPriority(priority)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    super.onLocationResult(result)
                    result.lastLocation?.let { location ->
                        _locationState.value = _locationState.value.copy(
                            lastKnownLocation = location,
                            isTracking = true
                        )
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )

            isTracking = true
            _locationState.value = _locationState.value.copy(
                priority = priority,
                isTracking = true
            )
        }
    }

    fun stopLocationUpdates() {
        viewModelScope.launch {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
            }
            isTracking = false
            _locationState.value = _locationState.value.copy(
                isTracking = false,
                priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
        }
    }

    fun clearError() {
        _locationState.value = _locationState.value.copy(error = null)
    }
}
