package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogDetailPageTest {
    @Test fun longRecordsAndManyEventsRemainReadableWithBoundedReplacementPages() {
        val entries = (0..79).map { index ->
            AppLogEntry(index.toLong(), "I", "Network", "event $index: " + "body 😀\n".repeat(if (index == 3) 5000 else 10), eventId = "event-$index")
        }
        val restored = mutableMapOf<Int, StringBuilder>()
        var cursor: AppLogDetailCursor? = AppLogDetailCursor()
        var pages = 0
        while (cursor != null) {
            val current = cursor
            val page = pageAppLogDetails(entries, current)
            assertTrue(page.rows.size <= 10)
            assertTrue(page.rows.sumOf { it.text.length } <= 16_384)
            page.rows.forEach { row ->
                assertTrue(row.text.length <= 4096)
                assertFalse(row.text.first().isLowSurrogate())
                assertFalse(row.text.last().isHighSurrogate())
                restored.getOrPut(row.recordNumber) { StringBuilder() }.append(row.text)
            }
            assertTrue(page.next == null || page.next != current)
            cursor = page.next
            pages++
            check(pages < 200)
        }
        assertTrue(pages > 1)
        assertEquals(entries.map { it.line() }, (1..entries.size).map { restored[it].toString() })
        assertEquals(80, restored.size)
    }

    @Test fun cursorAtTheEndIsEmptyAndRepeatedReadDoesNotMutateEvidence() {
        val entry = AppLogEntry(0, "E", "Runtime", "retained failure", eventId = "stable")
        val entries = listOf(entry)
        assertEquals(pageAppLogDetails(entries), pageAppLogDetails(entries))
        val end = pageAppLogDetails(entries, AppLogDetailCursor(entries.size))
        assertTrue(end.rows.isEmpty())
        assertNull(end.next)
        assertEquals("retained failure", entry.message)
    }
}
