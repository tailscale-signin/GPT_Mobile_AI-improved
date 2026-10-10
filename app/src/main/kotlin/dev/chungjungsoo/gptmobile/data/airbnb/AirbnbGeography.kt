package dev.chungjungsoo.gptmobile.data.airbnb

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Host-computed distances; unknown coordinates never imply a nearby match. */
internal object AirbnbGeography {
    fun filter(listings: List<AirbnbListing>, latitude: Double, longitude: Double, radiusKm: Double): List<AirbnbListing> {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 && radiusKm.isFinite() && radiusKm in 0.1..500.0)
        return listings.mapNotNull { listing ->
            val lat = listing.latitude
            val lon = listing.longitude
            if (lat == null || lon == null) return@mapNotNull listing.copy(distanceKm = null, distanceVerified = false)
            val distance = distance(latitude, longitude, lat, lon)
            listing.copy(distanceKm = distance, distanceVerified = true).takeIf { distance <= radiusKm }
        }.sortedWith(compareBy<AirbnbListing> { !it.distanceVerified }.thenBy { it.distanceKm ?: Double.MAX_VALUE })
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = sin(Math.toRadians(lat2 - lat1) / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(Math.toRadians(lon2 - lon1) / 2).let { it * it }
        return 6371.0088 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
