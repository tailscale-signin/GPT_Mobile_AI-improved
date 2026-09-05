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
    private val tool = DeviceLocationTool(locationProvider)

    @Test
    fun `definition exposes correct metadata and inputSchema`() {
        assertEquals(BuiltInAgentTool.DEVICE_LOCATION, tool.definition.name)
        val schema = tool.definition.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(JsonObject(emptyMap()), schema["properties"]?.jsonObject)
        assertEquals("false", schema["additionalProperties"]?.toString())
    }

    @Test
    fun `execute returns error when location permission is not granted`() = runTest {
        every { locationProvider.hasPermission() } returns false

        val result = tool.execute(callId = "call-1", arguments = JsonObject(emptyMap()))

        assertEquals("call-1", result.callId)
        assertTrue(result.isError)
        assertTrue(result.content is ToolResultContent.Text)
        assertEquals(
            "Location permission is not granted on this device.",
            (result.content as ToolResultContent.Text).text
        )
    }

    @Test
    fun `execute returns error when current location is unavailable`() = runTest {
        every { locationProvider.hasPermission() } returns true
        coEvery { locationProvider.getCurrentLocation(any()) } returns null

        val result = tool.execute(callId = "call-2", arguments = JsonObject(emptyMap()))

        assertEquals("call-2", result.callId)
        assertTrue(result.isError)
        assertTrue(result.content is ToolResultContent.Text)
        assertEquals(
            "Unable to determine device location at this time.",
            (result.content as ToolResultContent.Text).text
        )
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
