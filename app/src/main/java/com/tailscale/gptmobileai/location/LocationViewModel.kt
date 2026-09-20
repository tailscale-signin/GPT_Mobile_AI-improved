package com.tailscale.gptmobileai.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
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

    sealed class LocationState {
        object PermissionDenied : LocationState()
        object PermissionGranted : LocationState()
        data class Loading(val priority: Priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY) : LocationState()
        data class Success(
            val latitude: Double,
            val longitude: Double,
            val accuracy: Float,
            val timestamp: Long
        ) : LocationState()
        object Error : LocationState()
    }

    private val _locationState = MutableStateFlow<LocationState>(
        LocationState.Loading(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    )
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    fun requestPermissions() {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val fineLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION
                val coarseLocationPermission = Manifest.permission.ACCESS_COARSE_LOCATION

                when {
                    checkSelfPermission(fineLocationPermission) == PackageManager.PERMISSION_GRANTED -> {
                        _locationState.value = LocationState.PermissionGranted
                    }
                    checkSelfPermission(coarseLocationPermission) == PackageManager.PERMISSION_GRANTED -> {
                        _locationState.value = LocationState.PermissionGranted
                    }
                    else -> {
                        _locationState.value = LocationState.PermissionDenied
                    }
                }
            } else {
                _locationState.value = LocationState.PermissionGranted
            }
        }
    }

    fun getLastKnownLocation() {
        viewModelScope.launch {
            if (_locationState.value is LocationState.PermissionGranted) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        _locationState.value = LocationState.Success(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = System.currentTimeMillis()
                        )
                    } else {
                        _locationState.value = LocationState.Error
                    }
                }.addOnFailureListener { e ->
                    _locationState.value = LocationState.Error
                }
            }
        }
    }

    fun requestLocationUpdates(
        priority: Priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        minUpdateIntervalMillis: Long = 5000L
    ) {
        if (_locationState.value !is LocationState.PermissionGranted) return

        val locationRequest = LocationRequest.Builder(
            minUpdateIntervalMillis,
            0 // fastest interval for high accuracy
        ).setPriority(priority).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                result.lastLocation?.let { location ->
                    _locationState.value = LocationState.Success(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            viewModelScope.coroutineContext
        )
    }

    fun removeLocationUpdates() {
        if (_locationState.value is LocationState.PermissionGranted) {
            fusedLocationClient.removeLocationUpdates(
                object : LocationCallback() {},
                viewModelScope.coroutineContext
            )
        }
    }

    companion object {
        private const val TAG = "LocationViewModel"
    }
}
