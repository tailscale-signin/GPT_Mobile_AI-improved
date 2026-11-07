package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryActionTest {
    private fun user(id: Int, text: String) = MessageV2(id = id, chatId = 1, content = text, platformType = null)

    @Test fun shortOperationalFollowUpsRetainRootAcrossRestartWithoutRenewingBudget() {
        val task = "Test all authorized GitHub integration tools.\nBudget: at most 256 tool calls and 60 minutes."
        val commands = listOf("do the remaining work", "finalize", "show the full report", "proceed", "finish the remaining work")
        val history = mutableListOf(user(1, task))
        for ((index, command) in commands.withIndex()) {
            history += user(index + 2, command)
            val resolved = requireNotNull(resolveTaskRecovery(history))
            assertEquals(1, resolved.sourceMessageId)
            assertEquals(task, resolved.originalRequest)
            assertFalse(resolved.renewsToolBudget)
            assertFalse(resolved.retryUndispatchedBudgetFailures)
        }
        assertNull(resolveTaskRecovery(history + user(10, "Explain the water cycle") + user(11, "do the remaining work")))
        assertNull(resolveTaskRecovery(listOf(user(1, "show the full report"))))
    }

    @Test fun approvedCommitAndFailureQuestionsRetainTheOriginalTaskAcrossRestart() {
        val task = user(1, "Test all authorized GitHub integration tools")
        val commit = user(3, "GitHub workspace: o/r\nBranch: test-branch\n\nCommit ${"a".repeat(40)} was approved and published. Continue from the saved task using this exact commit. Subscribe with github_watch_checks when CI is pending; do not repeat the commit.")
        val messages = listOf(task, user(2, "why are you failing?"), commit)
        assertEquals(1, resolveTaskRecovery(messages)!!.sourceMessageId)
        assertEquals("1:1", rootWorkId(messages + user(4, "GitHub workspace: o/r\nBranch: test-branch\n\ncontinue"), "unused"))
        assertEquals(task.content, resolveTaskRecovery(messages)!!.originalRequest)
        assertFalse(resolveTaskRecovery(messages)!!.renewsToolBudget)
    }

    @Test fun equivalentIntegrationRequestsAndDiagnosticsKeepOriginalTask() {
        val original = user(1, "Test all GitHub tools")
        val messages = listOf(original, user(2, "continue"), user(3, "Why did the previous response stop?"), user(4, "test all"), user(5, "Why did the previous response stop?"), user(6, "continue"))
        assertEquals(1, resolveTaskRecovery(messages)!!.sourceMessageId)
        assertEquals("1:1", rootWorkId(messages, "unused"))
        assertFalse(resolveTaskRecovery(messages)!!.renewsToolBudget)
        assertEquals(8, resolveTaskRecovery(messages + user(7, "Inspect this file") + user(8, "Test all GitHub tools") + user(9, "continue"))!!.sourceMessageId)
    }

    @Test fun changedIntegrationScopeBecomesTheAuthoritativeTaskAcrossFurtherControls() {
        val original = user(1, "Test all GitHub tools and create disposable fixtures. Budget: 60 minutes.")
        val restricted = user(2, "Test all GitHub tools, read-only; do not create fixtures. Budget: 5 minutes.")
        assertNull(resolveTaskRecovery(listOf(original, restricted)))
        assertEquals("1:2", rootWorkId(listOf(original, restricted), "unused"))
        val resumed = requireNotNull(resolveTaskRecovery(listOf(original, restricted, user(3, "proceed"))))
        assertEquals(2, resumed.sourceMessageId)
        assertEquals(restricted.content, resumed.originalRequest)
        assertFalse(resumed.renewsToolBudget)
    }

    @Test fun onlyExplicitRetryDescriptorsCanReopenUndispatchedBudgetFailures() {
        val messages = listOf(user(1, "Inspect the repository"))
        val automatic = TaskResumeDescriptor("1:1", 1, "provider-resume:fixture", TaskRecoveryAction.RETRY_FAILED_STAGE)
        assertFalse(resolveTaskRecovery(messages, automatic)!!.retryUndispatchedBudgetFailures)
        assertFalse(resolveTaskRecovery(messages, automatic)!!.renewsToolBudget)
        assertTrue(resolveTaskRecovery(messages, automatic.copy(allowNewToolBudget = true))!!.renewsToolBudget)
        val explicit = automatic.copy(generationId = "user-retry", allowUndispatchedBudgetRetry = true)
        val encoded = kotlinx.serialization.json.Json.encodeToString(TaskResumeDescriptor.serializer(), explicit)
        val restored = kotlinx.serialization.json.Json.decodeFromString(TaskResumeDescriptor.serializer(), encoded)
        assertTrue(resolveTaskRecovery(messages, restored)!!.retryUndispatchedBudgetFailures)
        assertFalse(resolveTaskRecovery(messages, restored)!!.renewsToolBudget)
        for (action in TaskRecoveryAction.entries.filter { it != TaskRecoveryAction.RETRY_FAILED_STAGE }) {
            assertFalse(resolveTaskRecovery(messages, restored.copy(action = action))!!.retryUndispatchedBudgetFailures)
            assertFalse(resolveTaskRecovery(messages, restored.copy(action = action))!!.renewsToolBudget)
        }
        val legacy = """{"rootWorkId":"1:1","sourceMessageId":1,"generationId":"old","action":"RETRY_FAILED_STAGE"}"""
        val legacyRecovery = resolveTaskRecovery(messages, kotlinx.serialization.json.Json.decodeFromString<TaskResumeDescriptor>(legacy))!!
        assertFalse(legacyRecovery.retryUndispatchedBudgetFailures)
        assertFalse(legacyRecovery.renewsToolBudget)
    }

    @Test fun serializedRecoveryRequiresAnIndependentToolAllowanceGrantAndWritingNeverRenews() {
        val messages = listOf(user(1, "Inspect the repository with at most two tool calls"))
        for (action in TaskRecoveryAction.entries) {
            val ordinary = TaskResumeDescriptor("1:1", 1, "manual-${action.name}", action, allowUndispatchedBudgetRetry = true)
            val ordinaryJson = kotlinx.serialization.json.Json.encodeToString(TaskResumeDescriptor.serializer(), ordinary)
            assertFalse(ordinaryJson.contains("allowNewToolBudget"))
            val restoredOrdinary = kotlinx.serialization.json.Json.decodeFromString(TaskResumeDescriptor.serializer(), ordinaryJson)
            assertFalse(resolveTaskRecovery(messages, restoredOrdinary)!!.renewsToolBudget)

            val grantJson = kotlinx.serialization.json.Json.encodeToString(TaskResumeDescriptor.serializer(), ordinary.copy(allowNewToolBudget = true))
            val restoredGrant = kotlinx.serialization.json.Json.decodeFromString(TaskResumeDescriptor.serializer(), grantJson)
            assertEquals(action != TaskRecoveryAction.RETRY_WRITING, resolveTaskRecovery(messages, restoredGrant)!!.renewsToolBudget)
            assertEquals(action == TaskRecoveryAction.RETRY_FAILED_STAGE, resolveTaskRecovery(messages, restoredGrant)!!.retryUndispatchedBudgetFailures)
        }
    }

    @Test fun textRetryCommandsReopenBudgetRefusalsButContinueCommandsDoNot() {
        val original = user(1, "Inspect the repository")
        for (command in listOf("test again", "retest", "retry", "Retry full", "retry failed stage", "try again")) {
            val recovery = resolveTaskRecovery(listOf(original, user(2, command)))!!
            assertTrue(recovery.retryUndispatchedBudgetFailures)
            assertFalse(recovery.renewsToolBudget)
        }
        for (command in listOf("continue", "resume", "proceed", "retry writing", "proceed primary-only")) {
            val recovery = resolveTaskRecovery(listOf(original, user(2, command)))!!
            assertFalse(recovery.retryUndispatchedBudgetFailures)
            assertFalse(recovery.renewsToolBudget)
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
        assertFalse(recovery.renewsToolBudget)
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
