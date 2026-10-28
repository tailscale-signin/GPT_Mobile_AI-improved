package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class CombinedSingleDispatchPersistenceTest {
    @Test fun duplicateTapsShareOneDurableRunAndRemovedSourcesCannotBeFrozen() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val user = MessageV2(id = 2, chatId = 1, content = "Explain the world", platformType = null)
            val primary = MessageV2(id = 3, chatId = 1, content = "Primary answer.", platformType = "p", linkedMessageId = 2)
            val other = MessageV2(id = 4, chatId = 1, content = "Removed answer.", platformType = "q", linkedMessageId = 2)
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", enabledPlatform = listOf("p", "q"), activePlatform = listOf("p")))
            db.messageDao().addMessages(user, primary, other)
            val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p", "q"), mapOf("p" to "model", "q" to "model"), user.content, singlePass = true)
            val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", profileUid = "p")
            val sources = listOf(CombinedModelResponse("p", "Primary", content = primary.content))
            val snapshot = CombinedInputSnapshot(version = 6, snapshotId = "local-snapshot", localAssemblyReason = "WRITER_CONTEXT_CAPACITY_LOCAL_ASSEMBLY", userMessage = user, history = emptyList(), replies = emptyList(), sources = sources, prompt = "Frozen input", profileUid = "p", model = "model", systemPrompt = "", configKey = "config", combined = context, dispatchIdentity = "stable-identity").seal()
            fun request(id: String, input: CombinedInputSnapshot): PersistAgentRetryRequest {
                val raw = input.encode()
                return PersistAgentRetryRequest(user, primary.copy(combinedSources = input.sources), AgentRunDraft(id, "p", "CUSTOM", "model", synthesisKey = researchHash(raw), synthesisSnapshot = raw), freezeCombinedSources = true)
            }
            val dao = db.agentPersistenceDao()
            val stale = snapshot.copy(sources = sources + CombinedModelResponse("q", "Removed", content = other.content))
            assertTrue(runCatching { dao.persistAgentRetry(request("combined-synthesis:removed", stale)) }.exceptionOrNull()?.message.orEmpty().contains("COMBINED_MEMBERSHIP_CHANGED"))
            val results = (1..8).map { async { dao.persistAgentRetry(request("combined-synthesis:$it", snapshot)) } }.awaitAll()
            assertEquals(1, results.map { it.run.runId }.distinct().size)
            assertEquals(1, dao.synthesisRunsForTurn(1, 2).size)
            val finalId = requireNotNull(dao.combinedFinalMessage(1, 2)).finalMessageId
            assertTrue(finalId != 3 && finalId != 4)
            assertEquals(finalId, results.singleOrNull()?.assistantMessage?.id ?: results.first().assistantMessage.id)
            assertEquals(listOf("p"), dao.recoveryMessage(finalId)?.combinedSources?.map { it.platformUid })
            assertEquals("Primary answer.", dao.recoveryMessage(3)?.content)
            assertTrue(dao.recoveryMessage(3)!!.combinedSources.isEmpty())
            assertEquals(listOf("p"), dao.getChatRoom(1)?.activePlatform)
            assertEquals("Removed answer.", dao.recoveryMessage(4)?.content)
            val finalRun = results.first().run
            val local = requireNotNull(dao.publishLocalCombinedAssembly(finalRun.runId, finalRun.ownershipFence))
            assertTrue(local.content.contains("unified answer could not be completed"))
            assertFalse(local.content.contains(primary.content))
            assertEquals("SYNTHESIS_UNAVAILABLE", local.timeline.mapNotNull { it.combinedQuality }.single().status)
            assertFalse(local.timeline.mapNotNull { it.combinedQuality }.single().complete)
            assertEquals("FAILED", dao.recoveryRun(finalRun.runId)?.status)
            assertEquals(null, dao.recoveryRun(finalRun.runId)?.startedAt)
            assertEquals(null, dao.publishLocalCombinedAssembly(finalRun.runId, finalRun.ownershipFence))
            assertEquals("Primary answer.", dao.recoveryMessage(3)?.content)
        } finally {
            db.close()
        }
    }
}
