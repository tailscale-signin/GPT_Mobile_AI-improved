package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPlacesTest {
    @Test
    fun placesAreSortedFilteredAndCoordinatesValidated() {
        val json = Json.parseToJsonElement(
            """{"elements":[
            {"type":"node","id":1,"lat":44,"lon":-79,"tags":{"name":"Distant Shop"}},
            {"type":"way","id":2,"center":{"lat":43.001,"lon":-79},"tags":{"name":"Nearest Shop"}},
            {"type":"node","id":3,"lat":999,"lon":-79,"tags":{"name":"Invalid Shop"}}
        ]}"""
        ).jsonObject
        val places = parseNearbyPlaces(json, MapCoordinate(43.0, -79.0), "shop")
        assertEquals(listOf("way/2", "node/1"), places.map { it.id })
        assertTrue(places.first().distanceMeters in 100.0..120.0)
        assertTrue(parseNearbyPlaces(json, MapCoordinate(43.0, -79.0), "missing").isEmpty())
    }

    @Test
    fun routeUsesRoadGeometryAndReportedDistanceAndDuration() {
        val result = parsePlaceRoute(Json.parseToJsonElement("""{"code":"Ok","routes":[{"distance":420.0,"duration":90.0,"geometry":{"coordinates":[[-79,43],[-79.001,43.001],[-79.002,43.002]]}}]}""").jsonObject)
        assertEquals(3, result?.coordinates?.size)
        assertEquals(420.0, result?.distanceMeters)
        assertEquals(90.0, result?.durationSeconds)
        assertEquals(MapCoordinate(43.0, -79.0), result?.coordinates?.first())
    }

    @Test
    fun unavailableRouteNeverBecomesAFabricatedStraightLineRoute() {
        assertNull(parsePlaceRoute(Json.parseToJsonElement("""{"code":"NoRoute"}""").jsonObject))
        assertNull(parsePlaceRoute(Json.parseToJsonElement("""{"code":"Ok","routes":[{"distance":-1,"duration":10}]}""").jsonObject))
        assertFalse(MapCoordinate(Double.NaN, 0.0).isValid)
        assertEquals(0.0, distanceMeters(MapCoordinate(43.0, -79.0), MapCoordinate(43.0, -79.0)), 0.001)
    }
}
