package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
}
