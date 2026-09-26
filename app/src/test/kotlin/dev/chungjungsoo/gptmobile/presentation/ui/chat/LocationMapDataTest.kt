package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.tool.MapCoordinate
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationMapDataTest {
    @Test
    fun nearbyMarkersPreserveTheDeviceOriginAndDiscardInvalidPlaces() {
        val event = event(
            """{"latitude":43,"longitude":-79,"places":[
            {"id":"shop","name":"Store","latitude":43.001,"longitude":-79.001},
            {"id":"bad","name":"Invalid","latitude":999,"longitude":0}
        ]}"""
        )
        val data = locationMapData(listOf(event))
        assertEquals(MapCoordinate(43.0, -79.0), data?.origin)
        assertEquals(listOf("shop"), data?.places?.map { it.id })
    }

    @Test
    fun nestedPlaceCoordinatesNeverBecomeThePhoneLocation() {
        assertNull(locationMapData(listOf(event("""{"places":[{"latitude":43,"longitude":-79}]}"""))))
        assertNull(locationMapData(listOf(event("""{"latitude":999,"longitude":-79}"""))))
        assertNull(locationMapData(listOf(event("""{"latitude":43,"longitude":-79}""").copy(isError = true))))
    }

    private fun event(result: String) = ToolEvent(
        eventId = "event", runId = "run", sequence = 1, callId = "call",
        connectionUidSnapshot = null, connectionNameSnapshot = null,
        toolName = "device_location", modelToolName = "device_location", arguments = "{}",
        result = result, resultType = "JSON", status = ToolEventStatus.COMPLETED
    )
}
