package com.tailscale.gptmobileai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Location MCP Tool - Provides location services for the Android app.
 * Uses FusedLocationProviderClient for accurate and power-efficient location updates.
 */
@Singleton
@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideLocationManager(@ApplicationContext context: Context): LocationService {
        return LocationService(context)
    }
}

/**
 * Core location service using FusedLocationProviderClient.
 */
@SuppressLint("MissingPermission")
class LocationService @Inject constructor(
    private val context: Context
) {
    private val fusedLocationClient = android.location.LocationManagerCompat
        .getFusedLocationProviderClient(context.applicationContext)

    private val _locationState = MutableStateFlow(LocationState())
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var locationUpdates: LocationUpdates? = null

    /**
     * Get the last known location.
     */
    suspend fun getLastKnownLocation(): Location? {
        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            _locationState.value = LocationState(
                error = "Failed to get last known location: ${e.message}"
            )
            null
        }
    }

    /**
     * Request a single location update.
     */
    suspend fun requestLocation(): Location? {
        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            _locationState.value = LocationState(
                error = "Failed to request location: ${e.message}"
            )
            null
        }
    }

    /**
     * Start continuous location updates.
     */
    fun startLocationUpdates(
        minUpdateIntervalMillis: Long = 10_000,
        maxUpdateDistanceMeters: Float = 10f
    ) {
        if (!hasLocationPermission()) {
            _locationState.value = LocationState(
                error = "Location permission not granted"
            )
            return
        }

        val locationRequest = android.location.LocationRequest.Builder()
            .setPriority(android.location.LocationRequest.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(minUpdateIntervalMillis)
            .setMaxUpdateDistanceMeters(maxUpdateDistanceMeters)
            .build()

        locationUpdates = fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : android.location.LocationCallback() {
                override fun onLocationResult(result: android.location.LocationResult) {
                    val location = result.lastLocation
                    if (location != null) {
                        _locationState.value = LocationState(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.time,
                            speed = location.speed,
                            bearing = location.bearing,
                            altitude = location.altitude
                        )
                    }
                }

                override fun onLocationAvailable(location: android.location.Location) {
                    _locationState.value = LocationState(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = location.time,
                        speed = location.speed,
                        bearing = location.bearing,
                        altitude = location.altitude
                    )
                }

                override fun onLocationError(
                    error: android.location.LocationException,
                    params: android.location.LocationRequest?
                ) {
                    _locationState.value = LocationState(
                        error = "Location error: ${error.message}"
                    )
                }
            },
            context.applicationContext
        )
    }

    /**
     * Stop location updates.
     */
    fun stopLocationUpdates() {
        locationUpdates?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {
                _locationState.value = LocationState(
                    error = "Failed to stop location updates: ${e.message}"
                )
            }
            locationUpdates = null
        }
    }

    /**
     * Check if location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request location permission.
     */
    fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            context as? android.app.Activity ?: throw IllegalStateException(
                "Can only request permissions from an Activity"
            ),
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}

/**
 * Data class representing the current location state.
 */
data class LocationState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val timestamp: Long? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val altitude: Float? = null,
    val error: String? = null
) {
    fun isLocationAvailable(): Boolean = latitude != null && longitude != null

    fun getFormattedAddress(): String {
        if (!isLocationAvailable()) return "Location unavailable"
        
        val lat = String.format("%.6f", latitude!!)
        val lon = String.format("%.6f", longitude!!)
        return "$lat, $lon"
    }
}
