package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRecoveryLoaderTest {
    private val dao = mockk<AgentPersistenceDao>()
    private val settings = mockk<SettingRepository>()
    private val sessions = mockk<ResearchSessionStore>()
    private val profile = PlatformV2(uid = "profile", name = "Writer", model = "model")
    private val user = MessageV2(id = 1, chatId = 1, content = "Research the task", platformType = null)
    private val answer = MessageV2(id = 2, chatId = 1, content = "Saved answer", platformType = profile.uid, currentRunId = "old")
    private val reference = "1-${"a".repeat(64)}.recovery-corpus"

    private fun fixture(temporary: Boolean = false) {
        coEvery { dao.getIncompleteRuns(1, listOf(1)) } returns listOf(AgentRun("old", 1, 1, 2, profile.uid, "OPENAI", "model", status = "FAILED"))
        coEvery { dao.getToolEvents(listOf("old")) } returns emptyList()
        coEvery { dao.getMessages(1) } returns listOf(user, answer)
        coEvery { dao.getChatRoom(1) } returns ChatRoomV2(id = 1, title = "Research", enabledPlatform = listOf(profile.uid), isTemporary = temporary)
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { sessions.exportTurn(1, any(), any()) } returns emptyList()
    }

    private suspend fun load(onBlocked: (RecoveryBlock) -> Unit = {}): ResponseRecoveryContext? = ResearchRecoveryLoader(dao, settings, sessions).load(
        profile, "new", listOf(user), listOf(user), null, emptyList(), ModelDelegationSettings(), true, "policy", onBlocked
    )

    @Test fun persistentRecoveryReadsTheBoundCorpusWhileTemporaryRecoveryStaysInMemory() = runTest {
        fixture()
        coEvery { sessions.retainRecoveryCorpus(1, any(), any(), any()) } coAnswers { reference to arg<RecoveryEvidenceCorpus>(3) }
        val retained = requireNotNull(load())
        val page = retained.tool().execute("read", buildJsonObject {})
        assertEquals(reference, (page.content as ToolResultContent.Json).value.jsonObject.getValue("corpus_reference").jsonPrimitive.content)
        coVerify(exactly = 1) { sessions.retainRecoveryCorpus(1, any(), any(), any()) }

        fixture(temporary = true)
        val temporary = requireNotNull(load())
        val temporaryPage = (temporary.tool().execute("read", buildJsonObject {}).content as ToolResultContent.Json).value.jsonObject
        assertTrue("corpus_reference" !in temporaryPage)
        coVerify(exactly = 1) { sessions.retainRecoveryCorpus(1, any(), any(), any()) }
    }

    @Test fun profileRevokedWhilePersistingNeverReturnsSavedEvidenceToTheWriter() = runTest {
        fixture()
        coEvery { sessions.retainRecoveryCorpus(1, any(), any(), any()) } coAnswers {
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(enabled = false))
            reference to arg<RecoveryEvidenceCorpus>(3)
        }
        var blocked: RecoveryBlock? = null
        assertNull(load { blocked = it })
        assertEquals("PROFILE_DISABLED", blocked?.code)
    }

    @Test fun continuationsDoNotUseSiblingProfilesAsTheirCommittedAnswerOrToolEvidence() = runTest {
        fixture(temporary = true)
        val own = AgentRun("old", 1, 1, 2, profile.uid, "OPENAI", "model", status = "FAILED", createdAt = 1)
        val sibling = AgentRun("sibling", 1, 1, 3, "other-profile", "FREE", "other-model", status = "FAILED", createdAt = 2)
        val completedSibling = sibling.copy(runId = "sibling-complete", assistantMessageId = 4, status = "COMPLETED", createdAt = 3)
        coEvery { dao.getIncompleteRuns(1, listOf(1)) } returns listOf(own, sibling)
        coEvery { dao.getCompletedRuns(1) } returns listOf(completedSibling)
        coEvery { dao.getMessages(1) } returns listOf(
            user,
            answer,
            MessageV2(id = 3, chatId = 1, content = "Sibling incomplete answer", platformType = sibling.profileUid, currentRunId = sibling.runId),
            MessageV2(id = 4, chatId = 1, content = "Sibling complete answer", platformType = completedSibling.profileUid, currentRunId = completedSibling.runId)
        )
        for (action in listOf(null, ResolvedTaskRecovery(TaskRecoveryAction.CONTINUE_RUN, 1, user.content, false))) {
            val recovered = requireNotNull(
                ResearchRecoveryLoader(dao, settings, sessions).load(profile, "new", listOf(user), listOf(user), action, emptyList(), ModelDelegationSettings(), true, "policy") {}
            )
            assertEquals(answer.content, recovered.authorizedAnswer())
            assertTrue(recovered.content.contains(answer.content))
            assertFalse(recovered.content.contains("Sibling"))
            assertFalse(recovered.content.contains("other-model"))
        }
        coVerify(exactly = 2) { dao.getToolEvents(listOf("old")) }
        coVerify(exactly = 0) { dao.getToolEvents(match { "sibling" in it || "sibling-complete" in it }) }
    }

    @Test fun newProfileCannotAcquireAnotherProfilesDraftBySharingTheSameUserMessage() = runTest {
        fixture(temporary = true)
        coEvery { dao.getIncompleteRuns(1, listOf(1)) } returns listOf(AgentRun("sibling", 1, 1, 3, "other-profile", "FREE", "other-model", status = "FAILED"))
        assertNull(load())
        coVerify(exactly = 0) { dao.getToolEvents(any<List<String>>()) }
    }

    @Test fun sameSecondRetriesSelectTheNewestOwnedDraftBeforeAnOlderCompletedAnswer() = runTest {
        fixture(temporary = true)
        val older = AgentRun("z-older", 1, 1, 2, profile.uid, "OPENAI", "model", status = "COMPLETED", ownershipFence = 1, createdAt = 10)
        val newer = older.copy(runId = "a-newer", status = "INTERRUPTED", ownershipFence = 2)
        val retained = answer.copy(
            content = "",
            currentRunId = "new",
            ownershipFence = 3,
            revisions = listOf(
                AssistantRevision("Newest accumulated prefix", createdAt = 10, runId = newer.runId),
                AssistantRevision("Older completed short answer", createdAt = 10, runId = older.runId)
            )
        )
        coEvery { dao.getIncompleteRuns(1, listOf(1)) } returns listOf(newer)
        coEvery { dao.getCompletedRuns(1) } returns listOf(older)
        coEvery { dao.getToolEvents(listOf(older.runId, newer.runId)) } returns emptyList()
        coEvery { dao.getMessages(1) } returns listOf(user, retained)
        val recovered = requireNotNull(
            ResearchRecoveryLoader(dao, settings, sessions).load(profile, "new", listOf(user), listOf(user), ResolvedTaskRecovery(TaskRecoveryAction.CONTINUE_RUN, 1, user.content, false), emptyList(), ModelDelegationSettings(), true, "policy") {}
        )
        assertEquals("Newest accumulated prefix", recovered.authorizedAnswer())
        assertFalse(recovered.answerCompleted)
    }
}
