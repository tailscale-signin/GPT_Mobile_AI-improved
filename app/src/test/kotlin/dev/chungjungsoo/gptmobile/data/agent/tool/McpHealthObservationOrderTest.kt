package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class McpHealthObservationOrderTest {
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
