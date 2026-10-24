package dev.chungjungsoo.gptmobile.data.database.dao

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot
import dev.chungjungsoo.gptmobile.data.agent.CombinedWriterRetryFixture
import dev.chungjungsoo.gptmobile.data.agent.CombinedWriterRetrySnapshot
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CombinedWriterRetryPersistenceTest {
    @Test fun sameWriterRetryCannotReplaceTheCorpusOrReuseARootAndKeepsThePreviousDraft() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val original = CombinedWriterRetryFixture.snapshot()
            val raw = original.encode()
            val previous = AgentRun("combined-synthesis:previous", 1, 2, 3, "p", "CUSTOM", original.model, "FAILED", ownershipFence = 4, synthesisKey = researchHash(raw), synthesisSnapshot = raw)
            val answer = MessageV2(id = 3, chatId = 1, platformType = "p", linkedMessageId = 2, content = "The retained partial answer.", combinedSources = original.sources, currentRunId = previous.runId, ownershipFence = 4)
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", enabledPlatform = listOf("p"), activePlatform = listOf("p")))
            db.messageDao().addMessages(original.userMessage, answer)
            db.agentRunDao().upsert(previous)
            val dao = db.agentPersistenceDao()
            fun request(id: String, snapshot: CombinedInputSnapshot): PersistAgentRetryRequest {
                val record = snapshot.encode()
                return PersistAgentRetryRequest(original.userMessage, answer.copy(combinedSources = snapshot.sources), AgentRunDraft(id, "p", "CUSTOM", snapshot.model, synthesisKey = researchHash(record), synthesisSnapshot = record), replaceCombinedCorpus = true)
            }
            val retry = CombinedWriterRetrySnapshot.prepare(original, CombinedWriterRetryFixture.writer)
            val staleRoot = retry.copy(combined = retry.combined!!.copy(rootBudgetId = original.combined!!.rootBudgetId), inputDigest = "").seal()
            assertTrue(runCatching { dao.persistAgentRetry(request("combined-synthesis:stale-root", staleRoot)) }.exceptionOrNull() is IllegalArgumentException)
            val replaced = retry.copy(sources = retry.sources.map { it.copy(content = "Replaced contribution") }, inputDigest = "").seal()
            assertTrue(runCatching { dao.persistAgentRetry(request("combined-synthesis:replacement", replaced)) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, dao.synthesisRunsForTurn(1, 2).size)
            val accepted = dao.persistAgentRetry(request("combined-synthesis:retry", retry))
            assertNotEquals(previous.runId, accepted.run.runId)
            assertEquals(original.sources, accepted.assistantMessage.combinedSources)
            assertTrue(accepted.assistantMessage.revisions.any { it.content == answer.content && it.runId == previous.runId })
            val duplicate = dao.persistAgentRetry(request("combined-synthesis:double-tap", retry))
            assertEquals(accepted.run.runId, duplicate.run.runId)
            assertEquals(2, dao.synthesisRunsForTurn(1, 2).size)
        } finally {
            db.close()
        }
    }
}
