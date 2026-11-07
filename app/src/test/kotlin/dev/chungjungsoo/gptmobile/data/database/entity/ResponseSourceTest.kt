package dev.chungjungsoo.gptmobile.data.database.entity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSourceTest {
    @Test
    fun legacyHistoryRetainsUnknownSourceAndExecution() {
        val item = Json.decodeFromString<AssistantTimelineItem>("""{"type":"TEXT","content":"saved answer"}""")
        assertNull(item.responseSource)
        assertNull(listOf(item).uniformResponseSource())
        assertEquals(ResponseTransport.UNKNOWN, ResponseSource().transport)
        assertNull(ResponseSource(ResponseTransport.GATEWAY).executionLocation)
        assertFalse(ResponseSource(ResponseTransport.GATEWAY).gatewayVerified)
    }

    @Test
    fun legacyGatewayLabelsDoNotAcquireProofOnRestoreOrConversion() {
        val stored = """{"transport":"GATEWAY","profileUid":"remote-profile","modelId":"remote-model"}"""
        val restored = Json.decodeFromString<ResponseSource>(stored)
        assertEquals(ResponseTransport.GATEWAY, restored.transport)
        assertFalse(restored.gatewayVerified)
        assertEquals(restored, ResponseSourceConverter().decode(stored))
        assertEquals(restored, ResponseSourceConverter().decode(ResponseSourceConverter().encode(restored)))
        assertFalse(listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, "Saved remote answer", responseSource = restored)).uniformResponseSource()!!.gatewayVerified)
    }

    @Test
    fun receiptAndTimelineRoundTripIndependentlyOfProfileSelection() {
        val source = ResponseSource(ResponseTransport.GATEWAY, profileUid = "old-profile", modelId = "requested-model", gatewayVerified = true)
        val timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = GenerationReceipt(responseSource = source)),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "saved answer", responseSource = source)
        )
        assertEquals(timeline, Json.decodeFromString<List<AssistantTimelineItem>>(Json.encodeToString(timeline)))
        assertEquals(source, ResponseSourceConverter().decode(ResponseSourceConverter().encode(source)))
        assertEquals(source, timeline.uniformResponseSource())
        assertTrue(Json.decodeFromString<ResponseSource>(Json.encodeToString(source)).gatewayVerified)
    }

    @Test
    fun mixedAndUnattributedTextNeverInheritLastGatewayReceipt() {
        val gateway = ResponseSource(ResponseTransport.GATEWAY, gatewayVerified = true)
        val items = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "legacy"),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, generation = GenerationReceipt(responseSource = gateway)),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "gateway", responseSource = gateway)
        )
        assertNull(items.uniformResponseSource())
        assertEquals(listOf(null, gateway), responseSourceRanges("legacygateway", items).map { it.source })
        assertEquals(listOf(null), responseSourceRanges("edited body", items).map { it.source })
    }

    @Test
    fun mixedProofCannotUpgradeEarlierTextWhenTheSameProfileLaterUsesAGateway() {
        val unverified = ResponseSource(ResponseTransport.GATEWAY, profileUid = "same-profile")
        val verified = unverified.copy(gatewayVerified = true)
        val direct = ResponseSource(ResponseTransport.DIRECT, profileUid = "same-profile")
        val items = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, "legacy", responseSource = unverified),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, "gateway", responseSource = verified),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, "direct", responseSource = direct)
        )
        val restored = Json.decodeFromString<List<AssistantTimelineItem>>(Json.encodeToString(items))
        assertNull(restored.uniformResponseSource())
        assertEquals(listOf(unverified, verified, direct), responseSourceRanges("legacygatewaydirect", restored).map { it.source })
        assertEquals(listOf(false, true, false), restored.map { it.responseSource!!.gatewayVerified })
    }
}
