package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedOwnershipTest {
    @Test fun sourceChangesBeforeTheFreezeAreRejectedWithoutCreatingAnEditor() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val user = MessageV2(id = 2, chatId = 1, content = "History", platformType = null)
            val primary = MessageV2(id = 3, chatId = 1, content = "Primary topic", platformType = "primary", linkedMessageId = 2, currentRunId = "primary-source")
            val helper = MessageV2(id = 4, chatId = 1, content = "Helper topic plus late details", platformType = "helper", linkedMessageId = 2, currentRunId = "helper-source")
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined"))
            db.messageDao().addMessages(user, primary, helper)
            db.agentRunDao().upsert(AgentRun("primary-source", 1, 2, 3, "primary", "provider", "model", "COMPLETED"))
            db.agentRunDao().upsert(AgentRun("helper-source", 1, 2, 4, "helper", "provider", "model", "COMPLETED"))
            val stale = primary.copy(
                combinedSources = listOf(
                    dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("primary", "Primary", content = primary.content),
                    dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("helper", "Helper", content = "Helper topic")
                )
            )
            val dao = db.agentPersistenceDao()
            val rejected = runCatching { dao.persistAgentRetry(PersistAgentRetryRequest(user, stale, AgentRunDraft("combined-synthesis:stale", "primary", "provider", "model", synthesisKey = "stale"), freezeCombinedSources = true)) }
            assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
            assertEquals(null, dao.recoveryRun("combined-synthesis:stale"))
            val settled = stale.copy(combinedSources = stale.combinedSources.map { if (it.platformUid == "helper") it.copy(content = helper.content) else it })
            val accepted = dao.persistAgentRetry(PersistAgentRetryRequest(user, settled, AgentRunDraft("combined-synthesis:settled", "primary", "provider", "model", synthesisKey = "settled"), freezeCombinedSources = true))
            assertEquals(helper.content, accepted.assistantMessage.combinedSources.last().content)
        } finally {
            db.close()
        }
    }

    @Test fun explicitRecoveryCopiesOnlyVerifiedPrefixIntoTheNewOwnerAndKeepsFrozenInputs() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val platform = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "primary", name = "Primary", model = "test", enabled = true)
            val user = MessageV2(id = 2, chatId = 1, content = "Full task", platformType = null)
            val answer = MessageV2(id = 3, chatId = 1, content = "Saved chapter", platformType = "primary", currentRunId = "combined-synthesis:old", ownershipFence = 3)
            val snapshot = dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot(userMessage = user, history = emptyList(), replies = emptyList(), sources = listOf(dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("primary", "Primary", content = "Original contribution")), prompt = "Full frozen prompt", profileUid = "primary", model = "test", systemPrompt = "", configKey = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(platform))
            val json = kotlinx.serialization.json.Json
            val raw = json.encodeToString(dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot.serializer(), snapshot)
            val oldRun = AgentRun("combined-synthesis:old", 1, 2, 3, "primary", "CUSTOM", "test", "INTERRUPTED", ownershipFence = 3, synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(raw), synthesisSnapshot = raw)
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", combinedPrimaryUid = "primary"))
            db.messageDao().addMessages(user, answer)
            db.agentRunDao().upsert(oldRun)
            val dao = db.agentPersistenceDao()
            dao.preserveCombinedPrefix(answer)
            val resumed = dev.chungjungsoo.gptmobile.data.agent.CombinedRecoverySnapshot.prepare(oldRun, answer, "primary", platform, dao.answerSegments(oldRun.runId))
            val frozen = json.encodeToString(dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot.serializer(), resumed)
            val next = dao.persistAgentRetry(PersistAgentRetryRequest(user, answer, AgentRunDraft("combined-synthesis:new", "primary", "CUSTOM", "test", synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(frozen), synthesisSnapshot = frozen), resumeCombinedFrom = oldRun.runId))
            assertEquals("Saved chapter", next.assistantMessage.content)
            assertEquals(4L, next.assistantMessage.ownershipFence)
            assertEquals("Saved chapter", dao.answerSegments(next.run.runId).joinToString("") { it.text })
            db.agentRunDao().markRunning(next.run.runId, 1)
            assertFalse(dao.checkpointRunningMessage(answer.copy(content = "Late old writer")))
            assertTrue(dao.checkpointRunningMessage(next.assistantMessage.copy(content = "Saved chapter and continuation")))
            assertEquals("Saved chapter", dao.answerSegments(oldRun.runId).joinToString("") { it.text })
        } finally {
            db.close()
        }
    }

    @Test
    fun ix08NewCorpusCannotBeOverwrittenByOldWriterAndDuplicateStartAttaches() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val user = MessageV2(id = 2, chatId = 1, content = "Full task", platformType = null)
            val answer = MessageV2(id = 3, chatId = 1, content = "Original source", platformType = "primary", currentRunId = "source")
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined"))
            db.messageDao().addMessages(user, answer)
            db.agentRunDao().upsert(AgentRun("source", 1, 2, 3, "primary", "provider", "model", "COMPLETED"))
            val dao = db.agentPersistenceDao()
            val first = dao.persistAgentRetry(PersistAgentRetryRequest(user, answer, AgentRunDraft("combined-synthesis:a", "primary", "provider", "model", synthesisKey = "corpus-a", synthesisSnapshot = "full input")))
            assertEquals(1L, first.assistantMessage.ownershipFence)
            assertEquals(1, db.agentRunDao().markRunning(first.run.runId, 1))
            assertTrue(dao.checkpointRunningMessage(first.assistantMessage.copy(content = "Committed chapter")))
            val duplicate = dao.persistAgentRetry(PersistAgentRetryRequest(user, first.assistantMessage, AgentRunDraft("duplicate", "primary", "provider", "model", synthesisKey = "corpus-a")))
            assertEquals(first.run.runId, duplicate.run.runId)
            assertEquals("Committed chapter", duplicate.assistantMessage.content)
            assertEquals(1, db.agentRunDao().finishRunning(first.run.runId, "CANCELED", 2, null))
            val automaticReplacement = runCatching {
                dao.persistAgentRetry(PersistAgentRetryRequest(user, duplicate.assistantMessage, AgentRunDraft("combined-synthesis:late", "primary", "provider", "model", synthesisKey = "late-helper-117")))
            }
            assertTrue(automaticReplacement.exceptionOrNull() is IllegalArgumentException)
            assertEquals(first.run.runId, dao.recoveryMessage(answer.id)?.currentRunId)
            val second = dao.persistAgentRetry(PersistAgentRetryRequest(user, duplicate.assistantMessage, AgentRunDraft("combined-synthesis:b", "primary", "provider", "model", synthesisKey = "corpus-b"), replaceCombinedCorpus = true))
            assertEquals(2L, second.assistantMessage.ownershipFence)
            assertEquals(1, db.agentRunDao().markRunning(second.run.runId, 3))
            assertFalse(dao.checkpointRunningMessage(first.assistantMessage.copy(content = "Late writer")))
            assertFalse(dao.finishRunningWithMessage(first.assistantMessage.copy(content = "Late terminal"), first.run.runId, "COMPLETED", 4, null))
            assertTrue(dao.checkpointRunningMessage(second.assistantMessage.copy(content = "New corpus chapter")))
            assertEquals("Committed chapter", dao.answerSegments(first.run.runId).joinToString("") { it.text })
            assertEquals("New corpus chapter", dao.answerSegments(second.run.runId).joinToString("") { it.text })
        } finally {
            db.close()
        }
    }
}
