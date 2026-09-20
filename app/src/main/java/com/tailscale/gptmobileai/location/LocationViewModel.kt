package com.tailscale.gptmobileai.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Location ViewModel - Manages location state and operations.
 */
@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationService: LocationService
) : ViewModel() {

    val locationState: StateFlow<LocationState> = locationService.locationState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LocationState(error = "Initializing...")
        )

    fun requestLastKnownLocation() {
        viewModelScope.launch {
            locationService.getLastKnownLocation()
        }
    }

    fun requestSingleLocation() {
        viewModelScope.launch {
            locationService.requestLocation()
        }
    }

    fun startLocationUpdates() {
        locationService.startLocationUpdates()
    }

    fun stopLocationUpdates() {
        locationService.stopLocationUpdates()
    }

    fun hasLocationPermission(): Boolean = locationService.hasLocationPermission()

    fun requestLocationPermission() {
        locationService.requestLocationPermission()
    }

    private fun viewModelScope() = viewModelScope
}
