package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedRetainedContributionTest {
    private val user = MessageV2(id = 2, chatId = 1, content = "Explain the findings", platformType = null)
    private fun input(sources: List<CombinedModelResponse>, participants: List<String>, acknowledgements: Set<String> = emptySet()): CombinedInputSnapshot {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), participants, participants.associateWith { "model" }, user.content, singlePass = true)
        return CombinedInputSnapshot(userMessage = user, history = emptyList(), replies = emptyList(), sources = sources, prompt = "Frozen input", profileUid = "p", model = "model", systemPrompt = "", configKey = "config", combined = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", profileUid = "p"), dispatchIdentity = "one-dispatch", acknowledgedFailureAttempts = acknowledgements)
    }
    private fun request(primary: MessageV2, input: CombinedInputSnapshot): PersistAgentRetryRequest {
        val raw = input.encode()
        return PersistAgentRetryRequest(user, primary.copy(combinedSources = input.sources), AgentRunDraft("combined-synthesis:one", "p", "CUSTOM", "model", synthesisKey = researchHash(raw), synthesisSnapshot = raw), freezeCombinedSources = true)
    }

    @Test fun continueCanFreezeAvailableSubsetOnlyAfterCurrentFailureAcknowledgement() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val primary = MessageV2(id = 3, chatId = 1, content = "A complete useful contribution.", platformType = "p", linkedMessageId = 2, currentRunId = "success")
            val failed = MessageV2(id = 4, chatId = 1, content = "", platformType = "q", linkedMessageId = 2, currentRunId = "failure")
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", enabledPlatform = listOf("p", "q"), activePlatform = listOf("p", "q")))
            db.messageDao().addMessages(user, primary, failed)
            db.agentRunDao().upsert(AgentRun("success", 1, 2, 3, "p", "CUSTOM", "model", "COMPLETED"))
            db.agentRunDao().upsert(AgentRun("failure", 1, 2, 4, "q", "CUSTOM", "model", "FAILED"))
            val sources = listOf(CombinedModelResponse("p", "Primary", content = primary.content, attemptId = "success", rawContentDigest = researchHash(primary.content)))
            val blocked = input(sources, listOf("p", "q"))
            val dao = db.agentPersistenceDao()
            assertTrue(runCatching { dao.persistAgentRetry(request(primary, blocked)) }.exceptionOrNull()?.message.orEmpty().contains("COMBINED_FAILURE_DECISION_REQUIRED"))
            assertEquals(0, dao.synthesisRunsForTurn(1, 2).size)
            val allowed = dao.persistAgentRetry(request(primary, blocked.copy(acknowledgedFailureAttempts = setOf("failure"))))
            assertEquals(listOf("p"), allowed.assistantMessage.combinedSources.map { it.platformUid })
            assertEquals(listOf("p", "q"), dao.getChatRoom(1)?.activePlatform)
        } finally {
            db.close()
        }
    }

    @Test fun failedLatestAttemptCannotEraseTheFrozenPriorCompleteBody() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val prior = "The saved complete response retains a useful qualification."
            val primary = MessageV2(id = 3, chatId = 1, content = "", platformType = "p", linkedMessageId = 2, currentRunId = "latest", revisions = listOf(AssistantRevision(prior, createdAt = 1, runId = "best")))
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", enabledPlatform = listOf("p"), activePlatform = listOf("p")))
            db.messageDao().addMessages(user, primary)
            db.agentRunDao().upsert(AgentRun("latest", 1, 2, 3, "p", "CUSTOM", "model", "FAILED"))
            db.agentRunDao().upsert(AgentRun("best", 1, 2, 3, "p", "CUSTOM", "model", "COMPLETED"))
            val source = CombinedModelResponse("p", "Primary", content = prior, attemptId = "best", rawContentDigest = researchHash(prior), latestAttemptId = "latest", bestUsableAttemptId = "best", selectedContributionAttemptId = "best", claimExtractionVersion = 2)
            val frozen = input(listOf(source), listOf("p"), setOf("latest"))
            val dao = db.agentPersistenceDao()
            val rejected = frozen.copy(sources = listOf(source.copy(rawContentDigest = researchHash("Different body"))))
            assertTrue(runCatching { dao.persistAgentRetry(request(primary, rejected)) }.isFailure)
            val saved = dao.persistAgentRetry(request(primary, frozen))
            assertEquals(prior, saved.assistantMessage.combinedSources.single().content)
            assertEquals("best", saved.assistantMessage.combinedSources.single().selectedContributionAttemptId)
        } finally {
            db.close()
        }
    }
}
