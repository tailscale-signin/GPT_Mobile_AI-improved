package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationToolTest {

    private val locationProvider: DeviceLocationProvider = mockk()
    private val tool = DeviceLocationTool(locationProvider, mockk(relaxed = true))

    @Test
    fun `nearby request returns map markers while lookup failure preserves location`() = runTest {
        every { locationProvider.hasPermission() } returns true
        coEvery { locationProvider.getCurrentLocation(any()) } returns DeviceLocation(latitude = 43.0, longitude = -79.0, accuracy = null, altitude = null, timestamp = 10L, provider = "gps")
        val places = mockk<NearbyPlacesClient>()
        coEvery { places.nearby(any(), NearbyCategory.STORES, any(), any()) } returns listOf(NearbyPlace("node/1", "Shop", 43.001, -79.001, 140.0))
        val tools = DeviceLocationTool(locationProvider, places)
        val args = JsonObject(mapOf("nearby" to kotlinx.serialization.json.JsonPrimitive("stores")))
        val good = tools.execute("1", args).content as ToolResultContent.Json
        assertTrue(good.value.toString().contains("Shop"))
        coEvery { places.nearby(any(), any(), any(), any()) } throws IllegalStateException("offline")
        val failed = tools.execute("2", args)
        assertFalse(failed.isError)
        assertTrue((failed.content as ToolResultContent.Json).value.toString().contains("could not be loaded"))
    }

    @Test
    fun `definition exposes correct metadata and inputSchema`() {
        assertEquals(BuiltInAgentTool.DEVICE_LOCATION, tool.definition.name)
        val schema = tool.definition.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("nearby", "place_name", "radius_meters"), schema["properties"]?.jsonObject?.keys)
        assertEquals("false", schema["additionalProperties"]?.toString())
    }

    @Test
    fun `execute returns error when location permission is not granted`() = runTest {
        every { locationProvider.hasPermission() } returns false

        val result = tool.execute(callId = "call-1", arguments = JsonObject(emptyMap()))

        assertEquals("call-1", result.callId)
        assertTrue(result.isError)
        assertTrue(result.content is ToolResultContent.Text)
        val message = (result.content as ToolResultContent.Text).text
        assertTrue(message.contains("permission is not granted"))
        assertTrue(message.contains("Enable Device location"))
    }

    @Test
    fun `execute returns error when current location is unavailable`() = runTest {
        every { locationProvider.hasPermission() } returns true
        coEvery { locationProvider.getCurrentLocation(any()) } returns null

        val result = tool.execute(callId = "call-2", arguments = JsonObject(emptyMap()))

        assertEquals("call-2", result.callId)
        assertTrue(result.isError)
        assertTrue(result.content is ToolResultContent.Text)
        val message = (result.content as ToolResultContent.Text).text
        assertTrue(message.contains("No recent device location fix"))
        assertTrue(message.contains("Do not substitute a timezone"))
    }

    @Test
    fun `execute returns formatted json when location is available`() = runTest {
        val mockLocation = DeviceLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10.5f,
            altitude = 15.0,
            timestamp = 1715000000000L,
            provider = "gps"
        )
        every { locationProvider.hasPermission() } returns true
        coEvery { locationProvider.getCurrentLocation(any()) } returns mockLocation

        val result = tool.execute(callId = "call-3", arguments = JsonObject(emptyMap()))

        assertEquals("call-3", result.callId)
        assertFalse(result.isError)
        assertTrue(result.content is ToolResultContent.Json)

        val json = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals("37.7749", json["latitude"]?.jsonPrimitive?.content)
        assertEquals("-122.4194", json["longitude"]?.jsonPrimitive?.content)
        assertEquals("10.5", json["accuracy_meters"]?.jsonPrimitive?.content)
        assertEquals("15.0", json["altitude_meters"]?.jsonPrimitive?.content)
        assertEquals("1715000000000", json["timestamp"]?.jsonPrimitive?.content)
        assertEquals("gps", json["provider"]?.jsonPrimitive?.content)
    }
}
