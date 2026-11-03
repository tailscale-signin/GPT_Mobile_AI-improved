package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCatalogOmissionsTest {
    @Test fun repeatedCatalogsDoNotHideTransitionsOrOtherConnections() {
        val tracker = McpCatalogOmissions()
        assertTrue(tracker.changed("one", setOf("a", "b"), "recovering"))
        repeat(100) { assertFalse(tracker.changed("one", setOf("b", "a"), "recovering")) }
        assertTrue(tracker.changed("two", setOf("a", "b"), "recovering"))
        assertTrue(tracker.changed("one", setOf("a", "b"), "not_listed"))
        assertTrue(tracker.changed("one", setOf("a"), "not_listed"))
        assertFalse(tracker.changed("one", emptySet(), "not_listed"))
        assertTrue(tracker.changed("one", setOf("a"), "not_listed"))
    }

    @Test fun retainedConnectionsAreBounded() {
        val tracker = McpCatalogOmissions(2)
        for (id in listOf("one", "two", "three")) assertTrue(tracker.changed(id, setOf("a"), "recovering"))
        assertTrue(tracker.changed("one", setOf("a"), "recovering"))
    }
}
