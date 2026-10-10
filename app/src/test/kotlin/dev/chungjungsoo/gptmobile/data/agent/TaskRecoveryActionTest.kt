package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryActionTest {
    private fun user(id: Int, text: String) = MessageV2(id = id, chatId = 1, content = text, platformType = null)

    @Test fun `persisted recovery chains retain objective and primary only mode`() {
        val task = "Write 3,000 words of French history in chronological order, with sources."
        val saved = listOf(user(1, task), user(3, "Retry full"), user(5, "Proceed primary-only"), user(7, "Continue!"))
        val recovery = requireNotNull(resolveTaskRecovery(saved))
        assertEquals(task, recovery.originalRequest)
        assertEquals(1, recovery.sourceMessageId)
        assertEquals(TaskRecoveryAction.CONTINUE_RUN, recovery.action)
        assertTrue(recovery.primaryOnly)
        assertTrue(recovery.prompt().contains(task))
        assertTrue(recovery.prompt().contains("Do not search for the recovery command"))
    }

    @Test fun `a new objective ends the earlier primary only override`() {
        val recovery = requireNotNull(resolveTaskRecovery(listOf(user(1, "French history"), user(3, "primary-only"), user(5, "Explain eclipses"), user(7, "retry"))))
        assertEquals("Explain eclipses", recovery.originalRequest)
        assertFalse(recovery.primaryOnly)
    }

    @Test fun `ordinary requests containing control words remain new tasks`() {
        assertNull(TaskRecoveryAction.fromText("Explain primary research and elections"))
        assertNull(TaskRecoveryAction.fromText("Continue the story in a different style"))
        assertNull(resolveTaskRecovery(listOf(user(1, "Continue"))))
    }
}
