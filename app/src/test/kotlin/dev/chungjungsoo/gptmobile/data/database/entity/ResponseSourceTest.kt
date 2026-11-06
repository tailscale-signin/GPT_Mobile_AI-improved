package dev.chungjungsoo.gptmobile.data.database.entity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseSourceTest {
    @Test
    fun legacyHistoryRetainsUnknownSourceAndExecution() {
        val item = Json.decodeFromString<AssistantTimelineItem>("""{"type":"TEXT","content":"saved answer"}""")
        assertNull(item.responseSource)
        assertNull(listOf(item).uniformResponseSource())
        assertEquals(ResponseTransport.UNKNOWN, ResponseSource().transport)
        assertNull(ResponseSource(ResponseTransport.GATEWAY).executionLocation)
    }

    @Test
    fun receiptAndTimelineRoundTripIndependentlyOfProfileSelection() {
        val source = ResponseSource(ResponseTransport.GATEWAY, profileUid = "old-profile", modelId = "requested-model")
        val timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = GenerationReceipt(responseSource = source)),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "saved answer", responseSource = source)
        )
        assertEquals(timeline, Json.decodeFromString<List<AssistantTimelineItem>>(Json.encodeToString(timeline)))
        assertEquals(source, ResponseSourceConverter().decode(ResponseSourceConverter().encode(source)))
        assertEquals(source, timeline.uniformResponseSource())
    }

    @Test
    fun mixedAndUnattributedTextNeverInheritLastGatewayReceipt() {
        val gateway = ResponseSource(ResponseTransport.GATEWAY)
        val items = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "legacy"),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = GenerationReceipt(responseSource = gateway)),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "gateway", responseSource = gateway)
        )
        assertNull(items.uniformResponseSource())
        assertEquals(listOf(null, gateway), responseSourceRanges("legacygateway", items).map { it.source })
        assertEquals(listOf(null), responseSourceRanges("edited body", items).map { it.source })
    }
}
