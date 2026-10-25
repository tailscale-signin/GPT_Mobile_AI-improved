package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CombinedResponseProfileCacheTest {
    private val retained = "Portugal became an independent kingdom during the twelfth century. Later maritime exploration connected Atlantic ports with trade routes across several oceans."
    private val failed = MessageV2(id = 2, chatId = 1, linkedMessageId = 1, platformType = "p", currentRunId = "failed", content = "", revisions = listOf(AssistantRevision(retained, createdAt = 1, runId = "retained")))
    private val sibling = MessageV2(id = 3, chatId = 1, linkedMessageId = 1, platformType = "q", currentRunId = "streaming", content = "Still writing")
    private val runs = mapOf("failed" to run("failed", "FAILED"), "retained" to run("retained", "COMPLETED"), "streaming" to run("streaming", "RUNNING", "q"))

    @Test fun siblingStreamingAndRunMetadataDoNotRepeatArchivedAnswerAssessment() {
        val cache = CombinedResponseProfileCache()
        val first = profiles(cache, listOf(failed, sibling), runs).first()
        assertEquals("retained", first.message?.currentRunId)
        val changed = runs + ("retained" to runs.getValue("retained").copy(completedAt = 10)) + ("streaming" to runs.getValue("streaming").copy(status = "COMPLETED"))
        val again = profiles(cache, listOf(failed, sibling.copy(content = "The sibling has now finished its longer answer.")), changed, mapOf("p" to "Renamed")).first()
        assertSame(first.message, again.message)
        assertEquals("Renamed", again.name)
        assertEquals(CombinedResponseStatus.COMPLETED, again.status)
    }

    @Test fun archivedRunStatusAndEditedRevisionInvalidateTheProjection() {
        val cache = CombinedResponseProfileCache()
        val first = profiles(cache, listOf(failed), runs).single()
        val canceled = profiles(cache, listOf(failed), runs + ("retained" to runs.getValue("retained").copy(status = "CANCELED"))).single()
        assertNotSame(first.message, canceled.message)
        assertEquals("failed", canceled.message?.currentRunId)
        assertEquals(CombinedResponseStatus.FAILED, canceled.status)
        val edited = failed.copy(revisions = listOf(failed.revisions.single().copy(content = retained + " The republic was established in 1910.")))
        val updated = profiles(cache, listOf(edited), runs).single()
        assertNotSame(first.message, updated.message)
        assertEquals(edited.revisions.single().content, updated.message?.content)
    }

    @Test fun frozenSourceIdentityAndPreparingStateArePartOfTheCacheInput() {
        val cache = CombinedResponseProfileCache()
        val source = CombinedModelResponse("p", "Profile", content = retained, attemptId = "retained")
        val first = cache.projection("p", failed, source, false, runs)
        assertSame(first, cache.projection("p", failed, source, false, runs + ("unrelated" to run("unrelated", "FAILED"))))
        val changed = cache.projection("p", failed, source.copy(attemptId = "different-source", content = "A different frozen contribution."), false, runs)
        assertNotSame(first, changed)
        assertEquals("different-source", changed.message?.currentRunId)
        assertEquals("A different frozen contribution.", changed.message?.content)
        assertEquals(CombinedResponseStatus.GENERATING, cache.projection("empty", null, null, true, runs).status)
        assertEquals(CombinedResponseStatus.FAILED, cache.projection("empty", null, null, false, runs).status)
    }

    private fun profiles(cache: CombinedResponseProfileCache, messages: List<MessageV2>, states: Map<String, AgentRun>, names: Map<String, String> = emptyMap()) =
        combinedResponseProfiles(messages, listOf("p", "q"), names, setOf("p", "q"), false, states, cache)

    private fun run(id: String, status: String, uid: String = "p") = AgentRun(id, 1, 1, if (uid == "p") 2 else 3, uid, "FREE", "model", status)
}
