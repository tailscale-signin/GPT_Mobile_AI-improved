package com.tailscale.gptmobileai.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Location MCP Tool - Provides location services for the app
 * Uses FusedLocationProviderClient for best accuracy and battery efficiency
 */
class LocationMcpTool @Inject constructor(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {

    /**
     * Check if fine location permission is granted
     */
    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Check if coarse location permission is granted
     */
    fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Get current location once (single request)
     * Returns null if permission not granted or location unavailable
     */
    @Suppress("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        return try {
            val location = fusedLocationClient.getCurrentLocation(
                LocationRequest.PRIORITY_HIGH_ACCURACY,
                null
            ).await()
            location
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get current location as coordinates (lat/long)
     */
    @Suppress("MissingPermission")
    suspend fun getCurrentCoordinates(): Coordinates? {
        return getCurrentLocation()?.let { Location ->
            Coordinates(
                latitude = Location.latitude,
                longitude = Location.longitude
            )
        }
    }

    /**
     * Stream continuous location updates as a Flow
     * @param intervalMs Minimum interval between updates (default: 5000ms)
     * @param fastestIntervalMs Fastest update interval (default: 2000ms)
     * @param priority Location accuracy priority (default: HIGH_ACCURACY)
     */
    @Suppress("MissingPermission")
    fun locationUpdates(
        intervalMs: Long = 5000,
        fastestIntervalMs: Long = 2000,
        priority: Int = LocationRequest.PRIORITY_HIGH_ACCURACY
    ): Flow<Coordinates> = callbackFlow {
        // Check permission before starting
        if (!hasFineLocationPermission()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val locationRequest = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(fastestIntervalMs)
            .setMinUpdateDistanceMeters(5f) // Only update if moved 5+ meters
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(Coordinates(
                        latitude = location.latitude,
                        longitude = location.longitude
                    ))
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            kotlinx.coroutines.Dispatchers.Main.immediate
        )

        // Clean up when flow is closed
        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    /**
     * Request location permission (fine accuracy)
     */
    fun requestFineLocationPermission(): Boolean {
        return ActivityCompat.requestPermissions(
            context as? android.app.Activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request location permission (coarse accuracy)
     */
    fun requestCoarseLocationPermission(): Boolean {
        return ActivityCompat.requestPermissions(
            context as? android.app.Activity,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}

/**
 * Data class representing geographic coordinates
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double
) {
    /**
     * Check if this is a valid location (not default 0.0 values)
     */
    fun isValid(): Boolean = latitude != 0.0 && longitude != 0.0

    override fun toString(): String = "Coordinates(lat=$latitude, lng=$longitude)"
}