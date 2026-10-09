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

    @Test fun mixedCompletionTimesSelectTheLatestMatchingOwner() {
        val undated = event.copy(runId = "undated", sequence = 99)
        val older = event.copy(runId = "older", completedAt = 1L)
        val latest = event.copy(runId = "latest", completedAt = 2L)
        val profiles = mapOf("undated" to "undated-profile", "older" to "older-profile", "latest" to "latest-profile")
        for (events in listOf(listOf(undated, older, latest), listOf(latest, older, undated))) {
            assertEquals("latest-profile", toolResultOwner(events, profiles, "fallback") { true })
            assertEquals("older-profile", toolResultOwner(events, profiles, "fallback") { it.runId != "latest" })
        }
    }

    @Test fun equalCompletionTimesUseSequenceToSelectTheOwner() {
        for (completedAt in listOf(null, 0L, 2L)) {
            val first = event.copy(runId = "first", sequence = 1, completedAt = completedAt)
            val second = event.copy(runId = "second", sequence = 2, completedAt = completedAt)
            assertEquals("second-profile", toolResultOwner(listOf(first, second), mapOf("first" to "first-profile", "second" to "second-profile"), null) { true })
        }
    }
}
