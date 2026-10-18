package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CombinedLiveRevisionTest {
    @Test fun initialOutputKeepsItsAcceptedRevisionWhileRetriesGetStableIndependentIdentities() {
        val input = CombinedInputSnapshot(
            profileUid = "owner",
            model = "model",
            configKey = "config",
            systemPrompt = "system",
            prompt = "prompt",
            userMessage = MessageV2(chatId = 1, content = "question", platformType = null),
            history = emptyList(),
            replies = emptyList(),
            sources = emptyList(),
            revisionId = "accepted-initial"
        )
        assertEquals("accepted-initial", input.liveRevisionId("combined-synthesis:first"))
        val retry = input.copy(resumeFromRunId = "combined-synthesis:first")
        val firstRetry = retry.liveRevisionId("combined-synthesis:retry-one")
        assertNotEquals(input.revisionId, firstRetry)
        assertEquals(firstRetry, retry.liveRevisionId("combined-synthesis:retry-one"))
        assertNotEquals(firstRetry, retry.liveRevisionId("combined-synthesis:retry-two"))
        assertEquals(input.revisionId, retry.revisionId)
    }
}
