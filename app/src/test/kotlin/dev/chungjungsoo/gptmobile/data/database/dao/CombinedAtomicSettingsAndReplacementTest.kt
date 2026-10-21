package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.AnswerSegmentChain
import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.agent.CombinedReplacementCandidate
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedAtomicSettingsAndReplacementTest {
    @Test fun replacementArchivesLinkedRecordsAndRejectsOldFenceAndDigest() = runBlocking {
        val db = database()
        try {
            val dao = db.agentPersistenceDao()
            db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Combined"))
            val user = MessageV2(id = 2, chatId = 1, content = "Task", platformType = null)
            val oldReport = CombinedQualityReport(answerDigest = researchHash("Old draft"), revisionId = "old", correctionRequired = true)
            val old = MessageV2(
                id = 3,
                chatId = 1,
                content = "Old draft",
                platformType = "p",
                linkedMessageId = 2,
                currentRunId = "combined-synthesis:live",
                ownershipFence = 7,
                timeline = listOf(
                    AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = "Old draft"),
                    AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = oldReport)
                )
            )
            db.messageDao().addMessages(user, old)
            db.agentRunDao().upsert(AgentRun("combined-synthesis:live", 1, 2, 3, "p", "CUSTOM", "m", "RUNNING", ownershipFence = 7))
            dao.preserveCombinedPrefix(old)
            val report = CombinedQualityReport(answerDigest = researchHash("Corrected answer"), revisionId = "new", complete = true)
            val candidate = CombinedReplacementCandidate("old", oldReport.answerDigest, "new", "Corrected answer", report, CombinedEvidenceLedger(), "MATERIAL_CONTRADICTION")
            assertNull(dao.replaceCombinedAnswer("combined-synthesis:live", 7, candidate.copy(previousDigest = "stale")))
            val changed = requireNotNull(dao.replaceCombinedAnswer("combined-synthesis:live", 7, candidate))
            assertEquals(8L, changed.ownershipFence)
            assertEquals(report, changed.timeline.single { it.combinedQuality != null }.combinedQuality)
            assertEquals("Old draft", changed.revisions.first().content)
            assertEquals(oldReport, changed.revisions.first().timeline.single { it.combinedQuality != null }.combinedQuality)
            val archived = requireNotNull(changed.revisions.first().runId)
            assertEquals("Old draft", AnswerSegmentChain.text(dao.answerSegments(archived), archived, 7))
            assertEquals("Corrected answer", AnswerSegmentChain.text(dao.answerSegments("combined-synthesis:live"), "combined-synthesis:live", 8))
            assertFalse(dao.checkpointRunningMessage(old.copy(content = "Old draft stale callback")))
            assertNull(dao.replaceCombinedAnswer("combined-synthesis:live", 7, candidate))
            assertEquals(8L, dao.recoveryRun("combined-synthesis:live")?.ownershipFence)
        } finally {
            db.close()
        }
    }

    @Test fun settingsCommitAndRollbackKeepPrivacyAndRestorePreferredPrimary() = runBlocking {
        val db = database()
        try {
            val dao = db.agentPersistenceDao()
            val room = ChatRoomV2(id = 1, title = "Combined", enabledPlatform = listOf("a", "b"), activePlatform = listOf("b", "a"), combinedPrimaryUid = "b", isTemporary = true, draftText = "Keep draft")
            db.chatRoomDao().addChatRoom(room)
            dao.upsertModels(listOf(ChatPlatformModelV2(1, "a", "a-old"), ChatPlatformModelV2(1, "b", "b-old")))
            val previous = CombinedChatSettingsDraft("stable", 1, 0, participantUids = listOf("b", "a"), models = mapOf("a" to "a-old", "b" to "b-old"), creativity = 0.7f, primaryProfileUid = "b")
            val next = previous.copy(participantUids = listOf("a", "c"), models = mapOf("a" to "a-new", "c" to "c-new"), primaryProfileUid = null)
            val committed = dao.applyCombinedSettings(previous, next)
            assertEquals(listOf("a", "c"), committed.activePlatform)
            assertTrue(committed.isTemporary)
            assertEquals("Keep draft", committed.draftText)
            assertEquals(committed, dao.applyCombinedSettings(previous, next))
            val restored = dao.applyCombinedSettings(next, previous)
            assertEquals(previous.participantUids, restored.activePlatform)
            assertEquals("b", restored.combinedPrimaryUid)
            assertTrue(restored.isTemporary)
        } finally {
            db.close()
        }
    }

    @Test fun regenerationReplacesEverySelectedSlotAndArchivesRemovedParticipantsTogether() = runBlocking {
        val db = database()
        try {
            val (user, owner) = seedCompletedTurn(db)
            val dao = db.agentPersistenceDao()
            val accepted = regeneratedAcceptance()
            val result = dao.regenerateCombinedTurn(user, owner, regeneratedRuns(), accepted)
            assertEquals(listOf("a", "c"), result.runs.map { it.profileUid })
            assertTrue(result.assistantMessages.all { it.content.isEmpty() && it.combinedSources.isEmpty() })
            val nextOwner = result.assistantMessages.first()
            assertEquals("Original combined answer", nextOwner.revisions.first().content)
            assertEquals(owner.ownershipFence + 1, nextOwner.ownershipFence)
            assertEquals("new-root", nextOwner.timeline.single { it.combinedTurn != null }.combinedTurn?.rootBudgetId)
            assertEquals("old-root", CombinedAcceptedTurn.decode(result.runs.first().synthesisSnapshot)?.parentRootBudgetId)
            val removed = requireNotNull(dao.recoveryMessage(4))
            assertEquals("", removed.content)
            assertNull(removed.currentRunId)
            assertEquals("Original contributor", removed.revisions.first().content)
            assertTrue(runCatching { dao.regenerateCombinedTurn(user, owner, regeneratedRuns(), accepted) }.isFailure)
            assertEquals(nextOwner, dao.recoveryMessage(nextOwner.id))
        } finally {
            db.close()
        }
    }

    @Test fun aLateRegenerationInsertFailureRollsBackAllSlotsAndRevisions() = runBlocking {
        val db = database()
        try {
            val (user, owner) = seedCompletedTurn(db)
            val dao = db.agentPersistenceDao()
            val removedBefore = dao.recoveryMessage(4)
            val drafts = regeneratedRuns().mapIndexed { index, draft -> if (index == 1) draft.copy(runId = "prior-b") else draft }
            assertTrue(runCatching { dao.regenerateCombinedTurn(user, owner, drafts, regeneratedAcceptance()) }.isFailure)
            assertEquals(owner, dao.recoveryMessage(owner.id))
            assertEquals(removedBefore, dao.recoveryMessage(4))
            assertNull(dao.recoveryRun("new-a"))
            assertEquals(setOf("a", "b"), dao.rawGetMessages(1).mapNotNull { it.platformType }.toSet())
        } finally {
            db.close()
        }
    }

    @Test fun aCompletedProviderCannotPublishACorrectionRequiredCombinedDraftAsFinal() = runBlocking {
        val db = database()
        try {
            val (_, owner) = seedCompletedTurn(db)
            val dao = db.agentPersistenceDao()
            val run = requireNotNull(dao.recoveryRun(requireNotNull(owner.currentRunId))).copy(displayRole = "COMBINED_FINAL")
            dao.publishReply(run, owner, "COMPLETED", 99)
            assertEquals("PARTIAL", dao.publication("1:2:combined")?.deliveryKind)
            val report = CombinedQualityReport(
                answerDigest = researchHash(owner.content),
                revisionId = "checked",
                complete = false,
                deliveredScopeStatus = "PARTIAL",
                correctionRequired = true
            )
            val draft = owner.copy(timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report)))
            dao.publishReply(run, draft, "COMPLETED", 100)
            assertEquals("PARTIAL", dao.publication("1:2:combined")?.deliveryKind)
            val final = draft.copy(
                timeline = listOf(
                    AssistantTimelineItem(
                        AssistantTimelineItemType.NOTICE,
                        combinedQuality = report.copy(
                            complete = true,
                            deliveredScopeStatus = "COMPLETED_WITH_LIMITS",
                            correctionRequired = false
                        )
                    )
                )
            )
            dao.publishReply(run, final, "COMPLETED", 101)
            assertEquals("FINAL", dao.publication("1:2:combined")?.deliveryKind)
            val stale = final.copy(content = final.content + " Unchecked appendix")
            dao.publishReply(run, stale, "COMPLETED", 102)
            assertEquals("PARTIAL", dao.publication("1:2:combined")?.deliveryKind)
            val invalidFraming = final.copy(
                timeline = final.timeline.map { item -> item.copy(combinedQuality = item.combinedQuality?.copy(issues = listOf("INVALID_DELIVERY_RECORD"))) }
            )
            dao.publishReply(run, invalidFraming, "COMPLETED", 103)
            assertEquals("PARTIAL", dao.publication("1:2:combined")?.deliveryKind)
        } finally {
            db.close()
        }
    }

    private suspend fun seedCompletedTurn(db: ChatDatabaseV2): Pair<MessageV2, MessageV2> {
        db.chatRoomDao().addChatRoom(
            ChatRoomV2(
                id = 1,
                title = "Combined",
                conversationMode = "COMBINED",
                enabledPlatform = listOf("a", "b", "c"),
                activePlatform = listOf("a", "c"),
                combinedPrimaryUid = "a"
            )
        )
        val user = MessageV2(id = 2, chatId = 1, content = "Compare the results", platformType = null)
        val owner = MessageV2(
            id = 3,
            chatId = 1,
            platformType = "a",
            linkedMessageId = 2,
            content = "Original combined answer",
            currentRunId = "combined-synthesis:prior",
            ownershipFence = 3
        )
        val removed = MessageV2(
            id = 4,
            chatId = 1,
            platformType = "b",
            linkedMessageId = 2,
            content = "Original contributor",
            currentRunId = "prior-b",
            ownershipFence = 1
        )
        db.messageDao().addMessages(user, owner, removed)
        db.agentRunDao().upsert(AgentRun("combined-synthesis:prior", 1, 2, 3, "a", "CUSTOM", "old-a", "COMPLETED", ownershipFence = 3))
        db.agentRunDao().upsert(AgentRun("prior-b", 1, 2, 4, "b", "CUSTOM", "old-b", "COMPLETED", ownershipFence = 1))
        return user to owner
    }

    private fun regeneratedAcceptance(): CombinedAcceptedTurn {
        val requested = CombinedPolicyResolver.accept(
            ConversationCombinedSettings(collectionBias = 100, effort = 25),
            listOf("a", "c"),
            mapOf("a" to "new-model-a", "c" to "new-model-c"),
            "Compare the results",
            acceptedAtMillis = 1000
        )
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "new-root", chatId = 1)
        return CombinedAcceptedTurn(context = context, tools = ChatMcpToolConfig(combined = context), parentRootBudgetId = "old-root")
    }

    private fun regeneratedRuns() = listOf(
        AgentRunDraft("new-a", "a", "CUSTOM", "new-model-a", 2),
        AgentRunDraft("new-c", "c", "CUSTOM", "new-model-c", 2)
    )

    private fun database(): ChatDatabaseV2 = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
}
