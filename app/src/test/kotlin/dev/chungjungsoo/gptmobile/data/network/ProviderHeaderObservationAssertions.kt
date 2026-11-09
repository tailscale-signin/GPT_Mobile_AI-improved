package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseGatewayMetadataEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Headers are retained before parsing or rejection without claiming an answer or gateway execution. */
internal fun assertInitialChatHeaders(chunks: List<ChatCompletionChunk>, status: Int) {
    assertTrue(chunks.isNotEmpty())
    val first = chunks.first()
    assertEquals(status, first.providerResponseTiming?.httpStatus)
    assertNotNull(first.providerResponseTiming?.responseHeadersMs)
    assertNull(first.providerResponseTiming?.firstParsedPayloadMs)
    assertNotNull(first.reasoningControlReceipt)
    assertNull(first.choices)
    assertNull(first.error)
    assertNull(first.gatewayMetadata)
    assertNull(first.gatewayProgress)
    assertFalse(first.streamFinished)
}

internal fun assertInitialResponsesHeaders(events: List<ResponsesStreamEvent>, status: Int) {
    assertTrue(events.firstOrNull() is ResponseGatewayMetadataEvent)
    val first = events.first() as ResponseGatewayMetadataEvent
    assertEquals(status, first.providerResponseTiming?.httpStatus)
    assertNotNull(first.providerResponseTiming?.responseHeadersMs)
    assertNull(first.providerResponseTiming?.firstParsedPayloadMs)
    assertNull(first.metadata)
}
