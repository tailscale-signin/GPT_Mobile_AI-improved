package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedProfileFailureCacheTest {
    private val partial = MessageV2(id = 2, chatId = 1, linkedMessageId = 1, platformType = "p", currentRunId = "p-run", content = "Portugal became an independent kingdom during the twelfth century.")
    private val failed = AgentRun("p-run", 1, 1, 2, "p", "FREE", "model", "FAILED", terminalError = "The response is incomplete")
    private val sibling = MessageV2(id = 3, chatId = 1, linkedMessageId = 1, platformType = "q", currentRunId = "q-run", content = "Still writing")
    private val streaming = AgentRun("q-run", 1, 1, 3, "q", "FREE", "model", "RUNNING")

    @Test fun streamingSiblingDoesNotReassessTheUnchangedLongAnswer() {
        val cache = CombinedProfileFailureCache()
        val runs = mapOf("p-run" to failed, "q-run" to streaming)
        val first = cache.failures(1, "The history of Portugal in 5000 words", listOf(partial, sibling), runs, emptyMap(), emptyMap()).single()
        val again = cache.failures(1, "The history of Portugal in 5000 words", listOf(partial, sibling.copy(content = "A much longer response is now streaming.")), runs, emptyMap(), emptyMap()).single()
        assertSame(first, again)
        val renamed = cache.failures(1, "The history of Portugal in 5000 words", listOf(partial), runs, mapOf("p" to "Renamed profile"), emptyMap()).single()
        assertNotSame(first, renamed)
        assertEquals("Renamed profile", renamed.profileName)
    }

    @Test fun newRunOrEditedAnswerCannotReuseAStaleFailure() {
        val cache = CombinedProfileFailureCache()
        val request = "Explain Portugal's history"
        assertEquals(1, cache.failures(1, request, listOf(partial), mapOf("p-run" to failed), emptyMap(), emptyMap()).size)
        val retry = partial.copy(currentRunId = "retry", content = "")
        val retryRun = failed.copy(runId = "retry", status = "RUNNING", terminalError = null)
        assertTrue(cache.failures(1, request, listOf(retry), mapOf("retry" to retryRun), emptyMap(), emptyMap()).isEmpty())
        val completed = retry.copy(content = "Portugal developed from a medieval kingdom into a maritime empire and later a democratic republic.")
        assertTrue(cache.failures(1, request, listOf(completed), mapOf("retry" to retryRun.copy(status = "COMPLETED")), emptyMap(), emptyMap()).isEmpty())
        assertEquals(1, cache.failures(1, "The history of Portugal in 5000 words", listOf(completed), mapOf("retry" to retryRun.copy(status = "COMPLETED")), emptyMap(), emptyMap()).size)
    }

    @Test fun initialProviderWaitGetsItsDeadlineWithoutARecordedFailureAndRefreshesTheCache() {
        val cache = CombinedProfileFailureCache()
        val message = partial.copy(content = "")
        val waiting = failed.copy(status = AgentRunStatus.WAITING_PROVIDER_LIMIT, terminalError = null)
        val runs = mapOf(waiting.runId to waiting)
        val pending = cache.failures(1, "Question", listOf(message), runs, emptyMap(), emptyMap()).single()
        assertNull(pending.retryAfterMillis)
        assertFalse(pending.retryAllowed)
        assertEquals("Paused by provider limit", pending.shortStatus(1_000))

        val saved = ProviderWaitDeadline(waiting.runId, waiting.ownershipFence, 91_000)
        val first = cache.failures(1, "Question", listOf(message), runs, emptyMap(), emptyMap(), mapOf(saved.runId to saved)).single()
        assertNotSame(pending, first)
        assertEquals(saved.retryAt, first.retryAfterMillis)
        assertTrue(first.retryAllowed)
        assertEquals("RATE_LIMIT", first.category)
        assertEquals("Rate limited · 2m left", first.shortStatus(1_000))

        val changed = saved.copy(retryAt = 181_000)
        val updated = cache.failures(1, "Question", listOf(message), runs, emptyMap(), emptyMap(), mapOf(changed.runId to changed)).single()
        assertNotSame(first, updated)
        assertEquals(changed.retryAt, updated.retryAfterMillis)
        assertEquals("Rate limited · 3m left", updated.shortStatus(1_000))
        assertEquals("Retry available", updated.shortStatus(changed.retryAt))
        assertSame(updated, cache.failures(1, "Question", listOf(message), runs, emptyMap(), emptyMap(), mapOf(changed.runId to changed)).single())
    }

    @Test fun staleWaitDeadlineCannotAttachToAnotherMessageOwnerOrFence() {
        val message = partial.copy(content = "")
        val waiting = failed.copy(status = AgentRunStatus.WAITING_PROVIDER_LIMIT, terminalError = null)
        val saved = ProviderWaitDeadline(waiting.runId, waiting.ownershipFence, 91_000)
        val invalid = listOf(
            message to saved.copy(runId = "older-attempt"),
            message to saved.copy(fence = saved.fence + 1),
            message.copy(ownershipFence = saved.fence + 1) to saved,
            message.copy(id = message.id + 1) to saved,
            message.copy(chatId = message.chatId + 1) to saved,
            message to saved.copy(retryAt = 0)
        )
        invalid.forEach { (owner, deadline) ->
            val failure = combinedProfileFailures(1, "Question", listOf(owner), mapOf(waiting.runId to waiting), emptyMap(), emptyMap(), mapOf(waiting.runId to deadline)).single()
            assertNull(failure.retryAfterMillis)
            assertFalse(failure.retryAllowed)
            assertEquals("Paused by provider limit", failure.shortStatus(1_000))
        }
    }
}
