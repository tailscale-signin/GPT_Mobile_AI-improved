package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryActionTest {
    private fun user(id: Int, text: String) = MessageV2(id = id, chatId = 1, content = text, platformType = null)

    @Test fun onlyExplicitRetryDescriptorsCanReopenUndispatchedBudgetFailures() {
        val messages = listOf(user(1, "Inspect the repository"))
        val automatic = TaskResumeDescriptor("1:1", 1, "provider-resume:fixture", TaskRecoveryAction.RETRY_FAILED_STAGE)
        assertFalse(resolveTaskRecovery(messages, automatic)!!.retryUndispatchedBudgetFailures)
        val explicit = automatic.copy(generationId = "user-retry", allowUndispatchedBudgetRetry = true)
        val encoded = kotlinx.serialization.json.Json.encodeToString(TaskResumeDescriptor.serializer(), explicit)
        val restored = kotlinx.serialization.json.Json.decodeFromString(TaskResumeDescriptor.serializer(), encoded)
        assertTrue(resolveTaskRecovery(messages, restored)!!.retryUndispatchedBudgetFailures)
        for (action in TaskRecoveryAction.entries.filter { it != TaskRecoveryAction.RETRY_FAILED_STAGE }) {
            assertFalse(resolveTaskRecovery(messages, restored.copy(action = action))!!.retryUndispatchedBudgetFailures)
        }
        val legacy = """{"rootWorkId":"1:1","sourceMessageId":1,"generationId":"old","action":"RETRY_FAILED_STAGE"}"""
        assertFalse(resolveTaskRecovery(messages, kotlinx.serialization.json.Json.decodeFromString<TaskResumeDescriptor>(legacy))!!.retryUndispatchedBudgetFailures)
    }

    @Test fun textRetryCommandsReopenBudgetRefusalsButContinueCommandsDoNot() {
        val original = user(1, "Inspect the repository")
        for (command in listOf("retry", "Retry full", "retry failed stage", "try again")) {
            assertTrue(resolveTaskRecovery(listOf(original, user(2, command)))!!.retryUndispatchedBudgetFailures)
        }
        for (command in listOf("continue", "retry writing", "proceed primary-only")) {
            assertFalse(resolveTaskRecovery(listOf(original, user(2, command)))!!.retryUndispatchedBudgetFailures)
        }
    }

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

    @Test fun rootIdentitySurvivesControlMessagesButNeverCrossesChatsOrNewTasks() {
        val initial = user(1, "Compare durable materials")
        val continued = listOf(initial, user(3, "Continue"), user(5, "Retry failed stage"))
        assertEquals("1:1", rootWorkId(continued, "new-generation"))
        assertEquals("1:7", rootWorkId(continued + user(7, initial.content), "new-task"))
        assertEquals("2:9", rootWorkId(continued + user(9, "Continue").copy(chatId = 2), "different-chat"))
    }

    @Test fun explicitRetryDescriptorPreservesOriginalObjectiveAndRejectsOtherChatOrGuessedRoot() {
        val original = user(1, "Compare fixture materials")
        val descriptor = TaskResumeDescriptor("1:1", 1, "new-generation", TaskRecoveryAction.RETRY_FAILED_STAGE)
        assertEquals(original.content, resolveTaskRecovery(listOf(original), descriptor)!!.originalRequest)
        assertNull(resolveTaskRecovery(listOf(original.copy(chatId = 2)), descriptor))
        assertNull(resolveTaskRecovery(listOf(original), descriptor.copy(rootWorkId = "1:other")))
        val encoded = kotlinx.serialization.json.Json.encodeToString(TaskResumeDescriptor.serializer(), descriptor)
        assertEquals(descriptor, kotlinx.serialization.json.Json.decodeFromString(TaskResumeDescriptor.serializer(), encoded))
    }
}
