package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.tool.MapCoordinate
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirbnbMapDataTest {
    @Test fun everyLocatedListingIsMappedAndSortedRelativeToTheUser() {
        val listings = (1..30).map { listing(it.toString(), 43.0 + it * 0.001, -79.0) }
        val data = airbnbMapData(listings.reversed(), MapCoordinate(43.0, -79.0))!!
        assertEquals(30, data.places.size)
        assertEquals("1", data.places.first().id)
        assertTrue(data.showOrigin)
        assertEquals(MapCoordinate(43.0, -79.0), data.origin)
        assertTrue(data.places.zipWithNext().all { (a, b) -> a.distanceMeters <= b.distanceMeters })
    }

    @Test fun missingAndInvalidCoordinatesRemainUnmapped() {
        val data = airbnbMapData(listOf(listing("1", 43.0, -79.0), listing("2", null, null), listing("3", 999.0, 0.0)), null)!!
        assertEquals(listOf("1"), data.places.map { it.id })
        assertTrue(data.status!!.contains("2 listing(s) have no coordinates"))
        assertFalse(data.showOrigin)
        assertEquals(0.0, data.places.single().distanceMeters, 0.0)
        assertNull(airbnbMapData(listOf(listing("2", null, null)), MapCoordinate(43.0, -79.0)))
    }

    @Test fun absentOrInvalidUserPositionNeverClaimsADistanceFromTheUser() {
        val data = airbnbMapData(listOf(listing("1", 43.0, -79.0)), MapCoordinate(Double.NaN, 0.0))!!
        assertFalse(data.showOrigin)
        assertTrue(data.status!!.contains("Your position is unavailable"))
        assertEquals(MapCoordinate(43.0, -79.0), data.origin)
    }

    private fun listing(id: String, lat: Double?, lon: Double?) = AirbnbListing(id, "Stay $id", "https://www.airbnb.com/rooms/$id", latitude = lat, longitude = lon)
}
