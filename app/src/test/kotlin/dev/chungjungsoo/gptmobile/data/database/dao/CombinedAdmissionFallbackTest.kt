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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedAdmissionFallbackTest {
    @Test fun budgetFailurePublishesAttributedSourcesOnceWithoutCompletingTheWriterOrChangingContributors() = runBlocking {
        verifyFallback("QUEUED")
    }

    @Test fun runningWriterFailurePublishesThroughTheSameFencedTerminalTransaction() = runBlocking {
        verifyFallback("RUNNING")
    }

    private suspend fun verifyFallback(runStatus: String) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val user = MessageV2(id = 2, chatId = 1, content = "Explain the findings", platformType = null)
            val primary = MessageV2(id = 3, chatId = 1, content = "Primary unique finding.", platformType = "p", linkedMessageId = 2)
            val second = MessageV2(id = 4, chatId = 1, content = "A dissenting finding with a necessary qualification.", platformType = "q", linkedMessageId = 2)
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined", enabledPlatform = listOf("p", "q"), activePlatform = listOf("p", "q")))
            db.messageDao().addMessages(user, primary, second)
            val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p", "q"), mapOf("p" to "model", "q" to "model"), user.content, singlePass = true)
            val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", profileUid = "p")
            val sources = listOf(CombinedModelResponse("p", "Primary", content = primary.content), CombinedModelResponse("q", "Second", content = second.content))
            val snapshot = CombinedInputSnapshot(version = 6, snapshotId = "snapshot", localAssemblyReason = "WRITER_CONTEXT_CAPACITY_LOCAL_ASSEMBLY", userMessage = user, history = emptyList(), replies = emptyList(), sources = sources, prompt = "Frozen input", profileUid = "p", model = "model", systemPrompt = "", configKey = "config", combined = context, dispatchIdentity = "fallback-test").seal()
            val raw = snapshot.encode()
            val dao = db.agentPersistenceDao()
            val run = dao.persistAgentRetry(PersistAgentRetryRequest(user, primary.copy(combinedSources = sources), AgentRunDraft("combined-synthesis:fallback", "p", "CUSTOM", "model", synthesisKey = researchHash(raw), synthesisSnapshot = raw), freezeCombinedSources = true)).run
            if (runStatus == "RUNNING") dao.updateRunStatus(run.runId, "RUNNING", 1, null, null)
            assertNull(dao.publishRetainedCombinedFallback(run.runId, run.ownershipFence + 1, 10000, "COMBINED_CONTEXT_CAPACITY: too large"))
            assertNull(dao.publishRetainedCombinedFallback(run.runId, run.ownershipFence, 10000, "DISPATCH_POLICY_CHANGED: changed fields=tools."))
            val published = requireNotNull(dao.publishRetainedCombinedFallback(run.runId, run.ownershipFence, 10000, "DISPATCH_POLICY_CHANGED: changed fields=budget."))
            sources.forEach { assertTrue(published.content.contains(it.content)) }
            val report = published.timeline.mapNotNull { it.combinedQuality }.single()
            assertFalse(report.complete)
            assertEquals("PARTIAL", report.deliveredScopeStatus)
            assertNotNull(report.claimMapping)
            assertTrue(report.passages.isNotEmpty())
            assertEquals("FAILED", dao.recoveryRun(run.runId)?.status)
            val restored = requireNotNull(dao.recoveryMessage(published.id))
            assertEquals(published.content, restored.content)
            assertEquals(report.answerDigest, restored.timeline.mapNotNull { it.combinedQuality }.single().answerDigest)
            val publication = requireNotNull(dao.publication("1:2:combined"))
            assertEquals("PARTIAL", publication.deliveryKind)
            assertEquals(1L, publication.committedRevision)
            assertEquals(primary.content, dao.recoveryMessage(primary.id)?.content)
            assertEquals(second.content, dao.recoveryMessage(second.id)?.content)
            assertNull(dao.publishRetainedCombinedFallback(run.runId, run.ownershipFence, 10000, "COMBINED_CONTEXT_CAPACITY: too large"))
            assertEquals(publication, dao.publication("1:2:combined"))
        } finally {
            db.close()
        }
    }
}
