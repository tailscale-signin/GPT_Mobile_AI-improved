package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class McpHealthObservationOrderTest {
    @Test fun changedRecordRevisionInvalidatesEarlierEvidenceEvenForAnIdenticalTransportKey() {
        val order = McpHealthObservationOrder()
        val previous = order.begin("connection", "same-endpoint-and-token", "a".repeat(64))
        val current = order.begin("connection", "same-endpoint-and-token", "b".repeat(64))
        var state = "unknown"
        order.publish(previous) { state = "stale failure" }
        assertEquals("unknown", state)
        order.publish(current) { state = "fresh success" }
        assertEquals("fresh success", state)
        assertEquals("b".repeat(64), current.configurationRevision)
    }

    @Test
    fun olderResponseCannotReplaceNewerFailure() {
        val order = McpHealthObservationOrder()
        val old = order.begin("connection", "config")
        val newer = order.begin("connection", "config")
        var state = "unknown"
        order.publish(newer) { state = "failed" }
        order.publish(old) { state = "online" }
        assertEquals("failed", state)
    }

    @Test
    fun closeAndReopenWithIdenticalConfigRejectsOldResponse() {
        val order = McpHealthObservationOrder()
        val old = order.begin("connection", "config")
        order.invalidate("connection")
        val reopened = order.begin("connection", "config")
        var state = "unknown"
        order.publish(reopened) { state = "fresh" }
        order.publish(old) { state = "stale" }
        assertEquals("fresh", state)
    }

    @Test
    fun changedConfigAndCloseAllInvalidateInFlightObservations() {
        val order = McpHealthObservationOrder()
        val old = order.begin("connection", "old-config")
        val current = order.begin("connection", "new-config")
        var published = 0
        order.publish(old) { published++ }
        order.invalidateAll()
        order.publish(current) { published++ }
        assertEquals(0, published)
    }
}
