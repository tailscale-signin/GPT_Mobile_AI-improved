package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationWorkedTimeTest {
    @Test fun `timer formats full elapsed duration and clamps clock changes`() {
        assertEquals("Worked for 25m 37s", workedTimeText(1537))
        assertEquals("Worked for 5s", workedTimeText(5))
        assertEquals("Worked for 0s", workedTimeText(-1))
    }

    @Test fun `combined duration includes all workers and assembly`() {
        val first = run("first", 100, 150)
        val second = run("second", 110, 160)
        val combined = run("combined", 160, 162)
        assertEquals(GenerationTiming(100, 162), responseGenerationTiming(combined, listOf(first, second)))
        assertEquals(GenerationTiming(110, 160), responseGenerationTiming(second))
        assertEquals(GenerationTiming(100, null), responseGenerationTiming(combined.copy(completedAt = null), listOf(first, second)))
        assertNull(responseGenerationTiming(first.copy(startedAt = null, status = AgentRunStatus.QUEUED)))
    }

    private fun run(id: String, start: Long, end: Long) = AgentRun(id, 1, 1, 2, id, "provider", "model", AgentRunStatus.COMPLETED, createdAt = start, startedAt = start, completedAt = end)
}
