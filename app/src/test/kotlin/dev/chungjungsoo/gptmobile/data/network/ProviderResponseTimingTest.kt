package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayDeliveryTiming
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderResponseTimingTest {
    @Test fun delayedParsedEventsHaveIndependentImmutableTimestampsPerAttempt() {
        var now = 100L
        val observer = ProviderResponseObserver { now }
        now = 110L
        observer.headersReceived()
        assertEquals(ProviderResponseTiming(10L, null, null), observer.snapshot())
        now = 115L
        val heartbeat = observer.parsed(false)
        assertEquals(ProviderResponseTiming(10L, 15L, null), heartbeat)
        now = 350L
        val payload = observer.parsed(true)
        assertEquals(ProviderResponseTiming(10L, 15L, 250L), payload)
        assertEquals(null, heartbeat.firstParsedPayloadMs)
        now = 500L
        assertEquals(payload, observer.parsed(true))
        assertEquals(JsonNull, payload.evidence()["first_ui_paint_ms"])
        assertEquals(ProviderResponseTiming(null, 0L, 0L), ProviderResponseObserver { now }.parsed(true))
    }

    @Test fun asgiAcknowledgmentCannotBecomeSocketFlushOrUiPaintEvidence() {
        val timing = GatewayDeliveryTiming("gateway_asgi_stream", "completion_buffered", 10.0, 15.0, 100.0, 110.0, 120.0, 3)
        assertEquals(JsonNull, timing.evidence()?.get("socket_flush_confirmed"))
        assertNull(timing.copy(measurementScope = "socket_flush").evidence())
        assertNull(timing.copy(firstEventSubmitMs = Double.NaN).evidence())
    }
}
