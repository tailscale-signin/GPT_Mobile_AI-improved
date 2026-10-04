package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedAppLogsTest {
    @Test fun `consecutive repeats compress without losing count or last timestamp`() {
        val events = listOf(AppLogEntry(1000, "I", "Model", "Waiting"), AppLogEntry(1500, "I", "Model", "Waiting"), AppLogEntry(2000, "I", "Model", "Waiting"))
        val group = groupAppLogs(events).single()
        assertEquals(3, group.repetitions)
        assertEquals(1000L, group.entry.time)
        assertEquals(2000L, group.lastTime)
        assertEquals(3, events.size)
    }

    @Test fun `different severity intervening messages and distant repeats remain distinct`() {
        val first = AppLogEntry(1000, "I", "Model", "Waiting")
        val events = listOf(first, first.copy(time = 1500, level = "E"), first.copy(time = 2000), first.copy(time = 6000))
        assertEquals(4, groupAppLogs(events).size)
    }

    @Test fun `budget warnings across different engines group with raw details`() {
        val events = listOf(
            AppLogEntry(1000, "W", "ToolBudget", "Tool-result byte budget blocked execution · tool=one"),
            AppLogEntry(1500, "I", "Model", "Progress"),
            AppLogEntry(2000, "W", "ToolBudget", "Tool-result byte budget blocked execution · tool=two")
        )
        val groups = groupAppLogs(events)
        assertEquals(2, groups.size)
        assertEquals(2, groups.last().repetitions)
        assertEquals(listOf(events.first(), events.last()), groups.last().rawDetails)
    }
}
