package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolResultOwnerTest {
    private val event = ToolEvent("event", "helper-run", 1, "call", null, null, "search", "search", "{}", "{}", "JSON", ToolEventStatus.COMPLETED)

    @Test fun combinedCardsUseTheHelperProfileThatProducedTheResult() {
        assertEquals("helper", toolResultOwner(listOf(event), mapOf("helper-run" to "helper", "lead-run" to "lead"), "lead") { true })
    }

    @Test fun missingSourceProfileDoesNotBorrowPrimaryPermissions() {
        assertNull(toolResultOwner(listOf(event), mapOf("lead-run" to "lead"), "lead") { true })
        assertEquals("standalone", toolResultOwner(listOf(event), emptyMap(), "standalone") { true })
    }
}
