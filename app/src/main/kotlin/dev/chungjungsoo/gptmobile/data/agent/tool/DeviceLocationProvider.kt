package dev.chungjungsoo.gptmobile.data.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val timestamp: Long,
    val provider: String?
)

@Singleton
class DeviceLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun hasPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineLocation || coarseLocation
    }

    suspend fun getCurrentLocation(timeoutMillis: Long = 5000L): DeviceLocation? {
        if (!hasPermission()) {
            return null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val lastKnown = getLastKnownLocation(locationManager)
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 120_000) {
            return lastKnown.toDeviceLocation()
        }

        val freshLocation = withTimeoutOrNull(timeoutMillis) {
            requestSingleUpdate(locationManager)
        }

        return (freshLocation ?: lastKnown)?.toDeviceLocation()
    }

    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null) {
                        if (bestLocation == null || location.time > bestLocation.time) {
                            bestLocation = location
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Ignore if permission lost
            } catch (_: IllegalArgumentException) {
                // Ignore unsupported provider
            }
        }
        return bestLocation
    }

    @Suppress("DEPRECATION")
    private suspend fun requestSingleUpdate(locationManager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                }

                val executor = Executors.newSingleThreadExecutor()
                val provider = when {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                if (provider == null) {
                    executor.shutdown()
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                try {
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        executor
                    ) { location ->
                        executor.shutdown()
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                } catch (_: SecurityException) {
                    executor.shutdown()
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    executor.shutdown()
                    if (continuation.isActive) continuation.resume(null)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }

                try {
                    var requested = false
                    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                    for (p in providers) {
                        if (locationManager.isProviderEnabled(p)) {
                            locationManager.requestSingleUpdate(p, listener, Looper.getMainLooper())
                            requested = true
                            break
                        }
                    }
                    if (!requested) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            }
        }

    private fun Location.toDeviceLocation(): DeviceLocation {
        return DeviceLocation(
            latitude = latitude,
            longitude = longitude,
            accuracy = if (hasAccuracy()) accuracy else null,
            altitude = if (hasAltitude()) altitude else null,
            timestamp = time,
            provider = provider
        )
    }
}
