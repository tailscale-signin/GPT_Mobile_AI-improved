package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentDateToolTest {
    @Test
    fun `no argument tool exposes an explicit object schema accepted by strict providers`() {
        val schema = CurrentDateTool().definition.inputSchema

        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), schema.getValue("properties").jsonObject)
        assertEquals("false", schema.getValue("additionalProperties").toString())
    }

    @Test
    fun `execute returns formatted date time and zone from clock`() = runTest {
        val fixedInstant = Instant.parse("2025-05-18T14:30:45.123456Z")
        val zoneId = ZoneId.of("America/New_York")
        val fixedClock = Clock.fixed(fixedInstant, zoneId)
        val tool = CurrentDateTool(fixedClock)

        val result = tool.execute(callId = "call-123", arguments = JsonObject(emptyMap()))

        assertEquals("call-123", result.callId)
        assertFalse(result.isError)
        assertTrue(result.content is ToolResultContent.Json)

        val json = (result.content as ToolResultContent.Json).value
        assertEquals("2025-05-18", json["date"]?.jsonPrimitive?.content)
        assertEquals("10:30:45", json["time"]?.jsonPrimitive?.content)
        assertEquals("America/New_York", json["zone"]?.jsonPrimitive?.content)
    }
}
