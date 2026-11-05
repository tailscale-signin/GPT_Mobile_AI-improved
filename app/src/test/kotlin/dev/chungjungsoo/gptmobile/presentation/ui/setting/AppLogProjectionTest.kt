package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.groupAppLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogProjectionTest {
    @Test fun causalRowRetainsIdentityWhenItsRepresentativeBecomesAFailure() {
        val start = AppLogEntry(1000, "I", "Runtime", "LOAD_START · nativeOperationId=op-1")
        val failure = AppLogEntry(1100, "E", "Runtime", "LOAD_FAILED · nativeOperationId=op-1")
        val first = projectAppLogs(listOf(start), groupAppLogs(listOf(start)), false, "All tags", "")
        val next = projectAppLogs(listOf(start, failure), groupAppLogs(listOf(start, failure)), false, "All tags", "")
        assertEquals(first.rows.single().key, next.rows.single().key)
        assertEquals("error", next.rows.single().severity)
        assertEquals(2, next.visibleEvents)
        assertEquals(1, next.visibleIssues)
        assertEquals(1, next.errorEvents)
    }

    @Test fun filterCanFindRetainedDetailsThatAreNotTheGroupRepresentative() {
        val start = AppLogEntry(1000, "I", "Runtime", "LOAD_START · nativeOperationId=op-1")
        val failure = AppLogEntry(1100, "E", "Runtime", "LOAD_FAILED · nativeOperationId=op-1")
        val details = AppLogEntry(1200, "I", "Recovery", "Recovered pending checkpoint · nativeOperationId=op-1")
        val entries = listOf(start, failure, details)
        val projection = projectAppLogs(entries, groupAppLogs(entries), true, "Recovery", "checkpoint")
        assertEquals(1, projection.rows.size)
        assertEquals(entries, projection.rows.single().group.rawDetails)
        assertEquals(listOf("All tags", "Recovery", "Runtime"), projection.tags)
    }

    @Test fun longEventPreviewIsBoundedAndFullEvidenceIsRetained() {
        val entry = AppLogEntry(1000, "E", "Provider", "detail".repeat(1000))
        val projection = projectAppLogs(listOf(entry), groupAppLogs(listOf(entry)), false, "All tags", "")
        val row = projection.rows.single()
        assertTrue(row.preview.length < 2100)
        assertTrue(row.hasDetails)
        assertEquals(entry.message, row.group.rawDetails.single().message)
    }

    @Test fun projectedRowsAreNewestFirstAndBoundedWithoutChangingCounts() {
        val entries = (0..149).map { AppLogEntry(it.toLong(), "E", "Worker", "Failure $it") }
        val projection = projectAppLogs(entries, groupAppLogs(entries), false, "All tags", "")
        assertEquals(150, projection.totalEvents)
        assertEquals(150, projection.warningEvents)
        assertEquals(150, projection.errorEvents)
        assertEquals(100, projection.rows.size)
        assertEquals(149L, projection.rows.first().group.entry.time)
        assertEquals(50L, projection.rows.last().group.entry.time)
        assertFalse(projection.rows.first().hasDetails)
    }
}
