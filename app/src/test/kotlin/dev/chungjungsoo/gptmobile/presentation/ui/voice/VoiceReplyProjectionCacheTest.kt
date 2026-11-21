package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyProjectionCacheTest {
    @Test fun streamingOneReplyReusesHistoricalProjectionsAndTracksTerminalChanges() {
        val cache = VoiceReplyProjectionCache()
        val history = MessageV2(id = 1, content = "<!-- chat_subject: Summary -->\nA saved reply", platformType = "test", currentRunId = "old")
        val streaming = MessageV2(id = 2, content = "A streamed", platformType = "test", currentRunId = "new")
        val statuses = mapOf("old" to AgentRunStatus.COMPLETED, "new" to AgentRunStatus.RUNNING)
        val first = cache.update(listOf(listOf(history, streaming)), statuses, statuses::get)
        assertEquals("A saved reply", first[0].text)
        assertFalse(first[1].complete)
        val appended = listOf(listOf(history, streaming.copy(content = "A streamed reply")))
        val second = cache.update(appended, statuses, statuses::get)
        assertSame(first[0], second[0])
        assertNotSame(first[1], second[1])
        assertSame(second, cache.update(appended, statuses) { error("Unchanged sources must not be visited again") })
        val completed = statuses + ("new" to AgentRunStatus.FAILED)
        val terminal = cache.update(appended, completed, completed::get)
        assertSame(second[0], terminal[0])
        assertTrue(terminal[1].complete)
        assertTrue(terminal[1].failed)
        assertEquals("A streamed reply", cache.message(2)?.content)
        cache.update(listOf(listOf(history)), completed, completed::get)
        assertNull(cache.message(2))
    }
}
