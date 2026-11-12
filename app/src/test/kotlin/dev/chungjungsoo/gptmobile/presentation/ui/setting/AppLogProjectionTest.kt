package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.IncrementalAppLogGroups
import dev.chungjungsoo.gptmobile.data.diagnostics.groupAppLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogProjectionTest {
    @Test fun liveProjectionReusesUnchangedPreviewsAndUpdatesARecoveredIncident() {
        val grouping = IncrementalAppLogGroups()
        val projector = AppLogProjector()
        val failure = AppLogEntry(1000, "D", "Network", "Unexpected end of stream · incident_id=read-1")
        val other = AppLogEntry(1100, "I", "Model", "Started")
        val before = listOf(failure, other)
        val first = projector.project(before, grouping.update(before), false, "All tags", "")
        val recovery = AppLogEntry(1200, "I", "Gateway", "METADATA_READ_OUTCOME · incident_id=read-1 · outcome=COMPLETED")
        val after = before + recovery
        val next = projector.project(after, grouping.update(after), false, "All tags", "")
        assertSame(first.rows.first { it.group.entry == other }, next.rows.first { it.group.entry == other })
        assertEquals(0, next.visibleIssues)
        assertTrue(next.rows.first().preview.contains("Recovered; original events retained."))
        assertEquals(after.toSet(), next.rows.flatMap { it.group.rawDetails }.toSet())
    }

    @Test fun recoveredRowRemainsSearchableButNoLongerCountsAsUnresolved() {
        val failure = AppLogEntry(1000, "D", "Network", "Unexpected end of stream · incident_id=read-1")
        val recovery = AppLogEntry(1100, "I", "Gateway", "METADATA_READ_OUTCOME · incident_id=read-1 · outcome=COMPLETED")
        val entries = listOf(failure, recovery)
        val projection = projectAppLogs(entries, groupAppLogs(entries), true, "Network", "stream")
        assertEquals(2, projection.visibleEvents)
        assertEquals(0, projection.visibleIssues)
        assertEquals("info", projection.rows.single().severity)
        assertTrue(projection.rows.single().preview.contains("Recovered; original events retained."))
        assertEquals(entries, projection.rows.single().group.rawDetails)
    }

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

    @Test fun olderPagesRetainEveryMatchingRowAndFilterChangesClampThePage() {
        val entries = (0..248).map { AppLogEntry(it.toLong(), "E", "Worker", "Failure $it", eventId = "event-$it") }
        val groups = groupAppLogs(entries)
        val pages = (0..2).map { projectAppLogs(entries, groups, false, "All tags", "", pageIndex = it) }
        assertEquals(listOf(100, 100, 49), pages.map { it.rows.size })
        assertEquals(249, pages.flatMap { it.rows }.map { it.key }.distinct().size)
        assertEquals((248 downTo 0).map { "event-$it" }, pages.flatMap { it.rows }.map { it.key })
        assertTrue(pages[0].hasOlderRows)
        assertFalse(pages[2].hasOlderRows)
        val narrowed = projectAppLogs(entries, groups, false, "Worker", "Failure 248", pageIndex = 2)
        assertEquals(0, narrowed.pageIndex)
        assertEquals(1, narrowed.matchingRows)
        assertEquals("event-248", narrowed.rows.single().key)
    }
}
