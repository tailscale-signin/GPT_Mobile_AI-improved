package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.stopSavedGatewayRun
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCancelResult
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayConversationRepositoryTest {
    private lateinit var db: ChatDatabaseV2
    private lateinit var repository: GatewayConversationRepository
    private val settings = mockk<SettingRepository>()
    private val client = mockk<GatewayJobClient>()
    private val jobs = mockk<GatewayJobRepository>()
    private val commands = mockk<GatewayConversationCommandClient>()
    private val config = ProviderRequestConfig("http://192.168.1.9:8090", null, profileUid = "profile")
    private val route = GatewayJobRoute("profile", config.apiUrl.trimEnd('/'), config)
    private val key = gatewayIdentityKey("gateway", "owner", "conversation")
    private val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", title = "Original server conversation", profileId = "profile", model = "model", revision = 2)
    private var remote = emptyList<GatewayTranscriptMessage>()
    private val files = mutableListOf<String>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "Original profile", compatibleType = ClientType.LLAMA, apiUrl = route.endpoint, model = "model"))
        coEvery { jobs.originalRoute("profile", route.endpoint) } returns route
        coEvery { jobs.route("profile", useEngineeringGateway = false) } returns route
        coEvery { client.scope(config) } returns GatewayScopeIdentity("gateway", "owner")
        coEvery { client.identity(config) } returns GatewayScopeIdentity("gateway", "owner")
        coEvery { client.conversation(config, "conversation") } returns snapshot
        coEvery { client.messages(config, "conversation", any()) } answers {
            GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = remote.maxOfOrNull { it.revision } ?: 2, messages = remote, nextCursor = "saved-cursor")
        }
        repository = GatewayConversationRepository(db, settings, client, jobs, commands)
    }

    @After fun close() {
        db.close()
        files.forEach { RuntimeEnvironment.getApplication().deleteDatabase(it) }
    }

    private suspend fun turn(): PersistAgentTurnResult = db.agentPersistenceDao().persistAgentTurn(
        PersistAgentTurnRequest(ChatRoomV2(title = "Original local room", enabledPlatform = listOf("profile")), MessageV2(content = "Saved request", platformType = null), listOf(AgentRunDraft(UUID.randomUUID().toString(), "profile", "LLAMA", "model")), mapOf("profile" to "model"))
    )

    private suspend fun saveEngineering(turn: PersistAgentTurnResult) {
        GatewayEngineeringStore(db, settings).add(GatewayEngineeringBinding("profile", route.endpoint, GatewayEngineeringRequest("owner/repo", "work", "main", "Saved request", "model", chatId = turn.chatRoom.id, requestId = turn.runs.single().runId, profileId = "profile"), principalId = "owner", localUserMessageId = turn.userMessage.id, localAssistantMessageId = turn.assistantMessages.single().id, localRunId = turn.runs.single().runId, gatewayId = "gateway"))
    }

    private fun transcript(command: String) = listOf(
        GatewayTranscriptMessage("user", command, "user", "Saved request", "sent", 1, 1.0, "$command:user"),
        GatewayTranscriptMessage("assistant", command, "assistant", "Saved answer", "completed", 2, 2.0, "$command:assistant")
    )

    private suspend fun associate(turn: PersistAgentTurnResult) = repository.associateTurn(
        turn.chatRoom.id, "profile", route.endpoint, "gateway", "owner", "conversation", turn.runs.single().runId,
        turn.userMessage.id, turn.assistantMessages.single().id, turn.runs.single().runId
    )

    @Test fun legacyGatewayJobIdAloneDoesNotClaimAnOrdinaryOrCombinedConversation() = runBlocking {
        val turn = turn()
        db.agentRunDao().upsert(turn.runs.single().copy(gatewayJobId = "legacy-job", gatewayBaseUrl = route.endpoint))
        assertFalse(repository.ownsConversation(turn.chatRoom.id))
        assertNull(repository.conversationWriteBlocker(turn.chatRoom.id))
        coVerify(exactly = 0) { client.identity(any()) }
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, "saved-command", turn.userMessage.id, turn.assistantMessages.single().id, turn.runs.single().runId, "model")
        assertTrue(repository.ownsConversation(turn.chatRoom.id))
        assertNull(repository.conversationWriteBlocker(turn.chatRoom.id))
    }

    @Test fun originalOrdinaryConversationUsesItsChatModelAndBlocksChangedModelAccountOrParticipant() = runBlocking {
        val turn = turn()
        associate(turn)
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "Original", compatibleType = ClientType.LLAMA, apiUrl = route.endpoint, model = "different-global-default"))
        assertNull(repository.conversationWriteBlocker(turn.chatRoom.id))
        db.chatPlatformModelDao().upsertChatPlatformModel(ChatPlatformModelV2(turn.chatRoom.id, "profile", "changed-chat-model"))
        assertTrue(requireNotNull(repository.conversationWriteBlocker(turn.chatRoom.id)).contains("original model"))
        db.chatPlatformModelDao().upsertChatPlatformModel(ChatPlatformModelV2(turn.chatRoom.id, "profile", "model"))
        coEvery { client.identity(config) } returns GatewayScopeIdentity("different-gateway", "owner")
        assertTrue(requireNotNull(repository.conversationWriteBlocker(turn.chatRoom.id)).contains("account changed"))
        coEvery { client.identity(config) } returns GatewayScopeIdentity("gateway", "owner")
        db.chatRoomDao().updatePlatforms(turn.chatRoom.id, listOf("profile", "replacement"), listOf("replacement"), 1)
        assertTrue(requireNotNull(repository.conversationWriteBlocker(turn.chatRoom.id)).contains("original gateway participant"))
    }

    @Test fun receiptObservationCannotRecreateARoomDeletedDuringSnapshotRead() = runBlocking {
        val turn = turn()
        coEvery { client.conversation(config, "conversation") } coAnswers {
            db.chatRoomDao().deleteChatRooms(turn.chatRoom)
            snapshot
        }
        associate(turn)
        assertNull(db.gatewayJobDao().localChat(turn.chatRoom.id))
        assertTrue(db.chatRoomDao().getChatRooms().isEmpty())
        coVerify(exactly = 0) { client.messages(any(), any(), any()) }
    }

    @Test fun sameEndpointCannotChangeTheSavedOrdinaryProviderOrDowngradeAnImportedGatewayToCloud() = runBlocking {
        val turn = turn()
        associate(turn)
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "Changed", compatibleType = ClientType.OLLAMA, apiUrl = route.endpoint, model = "model"))
        assertTrue(requireNotNull(repository.conversationWriteBlocker(turn.chatRoom.id)).contains("provider type changed"))
        db.openHelper.writableDatabase.execSQL("DELETE FROM gateway_submissions")
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "Changed", compatibleType = ClientType.OPENAI, apiUrl = route.endpoint, model = "model"))
        assertTrue(requireNotNull(repository.conversationWriteBlocker(turn.chatRoom.id)).contains("private gateway provider"))
        coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "Original", compatibleType = ClientType.LLAMA, apiUrl = route.endpoint, model = "model"))
        assertNull(repository.conversationWriteBlocker(turn.chatRoom.id))
    }

    @Test fun retainedEngineeringCompletionProjectsBeforeOfflineReconnectButNeverRevivesRestoredHistory() = runBlocking {
        for (backupRestored in listOf(false, true)) {
            val turn = turn()
            val run = turn.runs.single()
            saveEngineering(turn)
            val store = GatewayEngineeringStore(db, settings)
            val binding = store.bindings(turn.chatRoom.id).single()
            val conversationId = if (backupRestored) "restored-conversation" else "conversation"
            val scopedSnapshot = snapshot.copy(conversationId = conversationId)
            coEvery { client.conversation(config, conversationId) } returns scopedSnapshot
            coEvery { client.messages(config, conversationId, any()) } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = conversationId, revision = 2, messages = transcript(run.runId), nextCursor = "saved-cursor")
            db.agentPersistenceDao().updateRunStatus(run.runId, "RUNNING", 1, null, null)
            repository.associateTurn(turn.chatRoom.id, "profile", route.endpoint, "gateway", "owner", conversationId, run.runId, run.userMessageId, run.assistantMessageId, run.runId)
            store.save(binding.copy(job = GatewayEngineeringJob("eng_" + "a".repeat(32), "owner/repo", "work", chatId = turn.chatRoom.id, state = "COMPLETED", revision = 2, createdAt = 1.0, deadline = 2.0, message = "Complete", gatewayId = "gateway", principalId = "owner", conversationId = conversationId, commandId = run.runId)))
            db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById(run.runId)).copy(status = "INTERRUPTED", terminalError = if (backupRestored) "BACKUP_RESTORED" else null))
            coEvery { client.conversation(config, conversationId) } throws IOException("offline")
            assertTrue(runCatching { repository.refreshChat(turn.chatRoom.id) }.isFailure)
            assertEquals(if (backupRestored) "" else "Saved answer", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
            assertEquals(if (backupRestored) "INTERRUPTED" else "COMPLETED", db.agentRunDao().getById(run.runId)?.status)
        }
    }

    @Test fun replayUsesOriginalSlotsAndPublishesExactlyOnceOnlyAfterLiveOwnershipDetaches() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        saveEngineering(turn)
        remote = transcript(run.runId)
        db.agentPersistenceDao().updateRunStatus(run.runId, "RUNNING", 1, null, null)
        associate(turn)
        assertEquals("", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertTrue(db.replyPublicationDao().all().isEmpty())
        db.agentPersistenceDao().updateRunStatus(run.runId, "WAITING_REMOTE_JOB", 1, null, null)
        repository.refreshChat(turn.chatRoom.id)
        repository.refreshChat(turn.chatRoom.id)
        assertEquals("Saved answer", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals("COMPLETED", db.agentRunDao().getById(run.runId)?.status)
        assertEquals(1, db.replyPublicationDao().all().size)
        assertEquals(listOf(run.userMessageId, run.assistantMessageId), db.gatewayJobDao().messages(key).map { it.localMessageId })
        assertEquals(2, messageCount(turn.chatRoom.id))
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun acceptedReceiptSurvivesFailedFirstTranscriptRead() = runBlocking {
        val turn = turn()
        saveEngineering(turn)
        remote = transcript(turn.runs.single().runId)
        coEvery { client.conversation(config, "conversation") } throws IOException("connection lost")
        assertTrue(runCatching { associate(turn) }.isFailure)
        assertEquals(turn.chatRoom.id, db.gatewayJobDao().conversation(key)?.localChatId)
        assertEquals(key, db.gatewayJobDao().submission(turn.runs.single().runId)?.conversationKey)
        coEvery { client.conversation(config, "conversation") } returns snapshot
        db.agentPersistenceDao().updateRunStatus(turn.runs.single().runId, "WAITING_REMOTE_JOB", 1, null, null)
        repository.refreshChat(turn.chatRoom.id)
        assertEquals("Saved answer", db.gatewayJobDao().localMessage(turn.assistantMessages.single().id)?.content)
        assertEquals(2, messageCount(turn.chatRoom.id))
    }

    @Test fun interruptedEngineeringOwnerReattachesAndPublishesUnderItsOriginalFence() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        saveEngineering(turn)
        remote = transcript(run.runId)
        val store = GatewayEngineeringStore(db, settings)
        val binding = store.bindings().single().copy(job = GatewayEngineeringJob("eng_" + "a".repeat(32), "owner/repo", "work", chatId = turn.chatRoom.id, state = "COMPLETED", revision = 2, createdAt = 1.0, deadline = 100.0, message = "Saved final result", gatewayId = "gateway", principalId = "owner", conversationId = "conversation", commandId = run.runId, detachSafe = true))
        store.save(binding)
        db.agentPersistenceDao().updateRunStatus(run.runId, "INTERRUPTED", 1, 2, "Observer ended")
        repository.recordEngineering(binding)
        assertEquals("COMPLETED", db.agentRunDao().getById(run.runId)?.status)
        assertEquals("Saved answer", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals(run.ownershipFence, db.gatewayJobDao().localMessage(run.assistantMessageId)?.ownershipFence)
        assertEquals(1, db.replyPublicationDao().all().size)
    }

    @Test fun acceptedReceiptBeforeAssociationReusesItsOriginalRoomAfterProcessDeath() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        saveEngineering(turn)
        remote = transcript(run.runId)
        val store = GatewayEngineeringStore(db, settings)
        val binding = store.bindings().single().copy(job = GatewayEngineeringJob("eng_" + "a".repeat(32), "owner/repo", "work", chatId = turn.chatRoom.id, state = "COMPLETED", revision = 2, createdAt = 1.0, deadline = 100.0, message = "Saved final result", gatewayId = "gateway", principalId = "owner", conversationId = "conversation", commandId = run.runId, detachSafe = true))
        store.save(binding)
        assertEquals(key, db.gatewayJobDao().submission(run.runId)?.conversationKey)
        assertNull(db.gatewayJobDao().conversation(key))
        db.agentPersistenceDao().updateRunStatus(run.runId, "WAITING_REMOTE_JOB", 1, null, null)
        val reopened = repository.openConversation("profile", "gateway", "owner", "conversation")
        assertEquals(turn.chatRoom.id, reopened.chatId)
        assertEquals(2, messageCount(turn.chatRoom.id))
        assertEquals("Saved answer", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals(1, db.replyPublicationDao().all().size)
    }

    @Test fun canceledOrSupersededMessageOwnershipCannotBeRewrittenBySavedTranscript() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        saveEngineering(turn)
        remote = transcript(run.runId)
        db.agentPersistenceDao().updateRunStatus(run.runId, "CANCELED", 1, 2, "Stopped by user")
        associate(turn)
        assertEquals("", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals("CANCELED", db.agentRunDao().getById(run.runId)?.status)
        assertTrue(db.replyPublicationDao().all().isEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET current_run_id='successor', ownership_fence=ownership_fence+1, content='Newer answer' WHERE message_id=?", arrayOf(run.assistantMessageId))
        repository.refreshChat(turn.chatRoom.id)
        assertEquals("Newer answer", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
    }

    @Test fun remoteOnlyConversationReopensSameRoomAndRebuildsDeletedRoomWithoutDuplicateServerCommands() = runBlocking {
        remote = transcript("remote-command")
        val first = repository.openConversation("profile", "gateway", "owner", "conversation")
        val second = repository.openConversation("profile", "gateway", "owner", "conversation")
        assertEquals(first.chatId, second.chatId)
        assertEquals(2, messageCount(first.chatId))
        db.chatRoomDao().deleteChatRooms(requireNotNull(db.gatewayJobDao().localChat(first.chatId)))
        val recreated = repository.openConversation("profile", "gateway", "owner", "conversation")
        assertNotEquals(first.chatId, recreated.chatId)
        assertEquals(2, messageCount(recreated.chatId))
        assertTrue(db.gatewayJobDao().messages(key).all { db.gatewayJobDao().localMessage(requireNotNull(it.localMessageId))?.chatId == recreated.chatId })
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun originalLocalHistoryIsNotInsertedAgainWhenServerSeedsItsDisplayTranscript() = runBlocking {
        val turn = turn()
        val command = turn.runs.single().runId
        remote = listOf(GatewayTranscriptMessage("historic", "$command:history:0", "user", "Earlier history", "completed", 1, 0.0, "$command:history:0")) + transcript(command).map { it.copy(revision = it.revision + 1) }
        associate(turn)
        assertEquals(2, messageCount(turn.chatRoom.id))
        assertNull(db.gatewayJobDao().message(key, "historic")?.localMessageId)
    }

    @Test fun differentAccountOrInstallationCannotAdoptAnExistingRemoteConversation() = runBlocking {
        remote = transcript("remote-command")
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(principalId = "different-account")
        assertTrue(runCatching { repository.openConversation("profile", "gateway", "owner", "conversation") }.isFailure)
        assertNull(db.gatewayJobDao().conversation(key))
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(gatewayId = "new-installation")
        assertTrue(runCatching { repository.openConversation("profile", "gateway", "owner", "conversation") }.isFailure)
        assertNull(db.gatewayJobDao().conversation(key))
    }

    @Test fun expiredCursorHydratesWithGetAndMissingProfileDoesNotSubstituteCurrentProfile() = runBlocking {
        remote = transcript("remote-command")
        val first = repository.openConversation("profile", "gateway", "owner", "conversation")
        coEvery { client.messages(config, "conversation", "saved-cursor") } throws GatewayCursorExpired()
        repository.refreshChat(first.chatId)
        assertEquals(2, messageCount(first.chatId))
        coVerify(atLeast = 2) { client.messages(config, "conversation", null) }
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(conversationId = "unbound", profileId = null)
        coEvery { client.conversation(config, "unbound") } returns snapshot.copy(conversationId = "unbound", profileId = null)
        assertTrue(runCatching { repository.openConversation("profile", "gateway", "owner", "unbound") }.isFailure)
    }

    @Test fun boundSendKeepsOriginalModelAndRouteWhileUnrelatedProfileCanHaveItsOwnTurn() = runBlocking {
        remote = transcript("remote-command")
        val opened = repository.openConversation("profile", "gateway", "owner", "conversation")
        assertEquals("conversation", repository.requestBinding(opened.chatId, "profile", route.endpoint, "model")?.conversationId)
        assertTrue(runCatching { repository.requestBinding(opened.chatId, "profile", route.endpoint, "different-model") }.isFailure)
        assertTrue(runCatching { repository.requestBinding(opened.chatId, "profile", "http://192.168.1.10", "model") }.isFailure)
        assertNull(repository.requestBinding(opened.chatId, "other-profile", route.endpoint, "model"))
        assertEquals("model", repository.boundSnapshot(opened.chatId, "profile", "model")?.second?.model)
    }

    @Test fun lostOrdinaryHeadersRecoverTheOriginalRoomButLeaveVerificationToTheExistingRecoveryPolicy() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        val command = "frozen-request-command"
        remote = transcript(command)
        db.agentPersistenceDao().updateRunStatus(run.runId, "RUNNING", 1, null, null)
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, command, turn.userMessage.id, run.assistantMessageId, run.runId, "model")
        assertEquals("RESERVED_ORDINARY", db.gatewayJobDao().submission(command)?.state)
        repository.ordinaryDispatching(command, run.runId)
        db.agentPersistenceDao().updateRunStatus(run.runId, "INTERRUPTED", 1, 2, "Process stopped")
        coEvery { client.observeCommand(config, command) } returns null
        repository.reconcileOrdinaryAdmissions(turn.chatRoom.id)
        assertNull(db.agentRunDao().getById(run.runId)?.gatewayJobId)
        coEvery { client.command(config, command) } returns GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = command, conversationId = "conversation", jobKind = "generation", jobId = "saved-job", profileId = "profile", model = "model")
        val reopened = repository.openConversation("profile", "gateway", "owner", "conversation")
        assertEquals(turn.chatRoom.id, reopened.chatId)
        assertEquals("saved-job", db.agentRunDao().getById(run.runId)?.gatewayJobId)
        assertEquals("INTERRUPTED", db.agentRunDao().getById(run.runId)?.status)
        assertEquals("", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals(2, messageCount(turn.chatRoom.id))
        assertEquals("ACCEPTED", db.gatewayJobDao().submission(command)?.state)
        coVerify(exactly = 1) { client.observeCommand(config, command) }
        coVerify(exactly = 1) { client.command(config, command) }
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun repeatedAbsentReceiptPersistsBackoffAcrossRepositoryRestartAndLaterRecoversOriginalJob() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        val command = "missing-receipt"
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, command, turn.userMessage.id, run.assistantMessageId, run.runId, "model")
        db.agentPersistenceDao().updateRunStatus(run.runId, "RUNNING", 1, null, null)
        repository.ordinaryDispatching(command, run.runId)
        db.agentPersistenceDao().updateRunStatus(run.runId, "INTERRUPTED", 1, 2, "GATEWAY_OUTCOME_UNKNOWN: response lost")
        coEvery { client.observeCommand(config, command) } returns null
        repeat(20) {
            GatewayConversationRepository(db, settings, client, jobs, commands).reconcileOrdinaryAdmissions(turn.chatRoom.id)
        }
        coVerify(exactly = 1) { client.observeCommand(config, command) }
        val pending = requireNotNull(db.gatewayJobDao().submission(command))
        assertEquals("DISPATCHING_ORDINARY", pending.state)
        assertEquals("INTERRUPTED", db.agentRunDao().getById(run.runId)?.status)
        assertTrue(pending.receipt?.contains("gateway_command_lookup_v1") == true)
        // Advance only the durable lookup checkpoint, then discover a late acceptance.
        db.gatewayJobDao().saveSubmission(GatewayCommandReconciliation.deferred(pending, System.currentTimeMillis() - 600_000))
        coEvery { client.observeCommand(config, command) } returns GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = command, conversationId = "original-conversation", jobKind = "generation", jobId = "original-job", profileId = "profile", model = "model")
        repository.reconcileOrdinaryAdmissions(turn.chatRoom.id)
        assertEquals("ACCEPTED", db.gatewayJobDao().submission(command)?.state)
        assertEquals("original-job", db.agentRunDao().getById(run.runId)?.gatewayJobId)
        coVerify(exactly = 2) { client.observeCommand(config, command) }
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun durableRejectionSettlesOnlyOriginalActiveOwnerAndDoesNotOverwriteExplicitStop() = runBlocking {
        for (status in listOf("WAITING_REMOTE_JOB", "INTERRUPTED", "CANCELED")) {
            val turn = turn()
            val run = turn.runs.single()
            val command = "rejected-$status"
            repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, command, turn.userMessage.id, run.assistantMessageId, run.runId, "model")
            db.agentPersistenceDao().updateRunStatus(run.runId, status, 1, 2, if (status == "CANCELED") "Stopped by user" else "GATEWAY_OUTCOME_UNKNOWN: response lost")
            val rejection = GatewayCommandRejection(1, "gateway", "owner", command, "generation", "rejected", "EXECUTION_MODEL_LOADING", 409, "provider_admission", "not_dispatched", false)
            coEvery { client.observeCommand(config, command) } throws GatewayCommandRejected(rejection)
            repository.reconcileOrdinaryAdmissions(turn.chatRoom.id)
            repository.reconcileOrdinaryAdmissions(turn.chatRoom.id)
            val retained = requireNotNull(db.agentRunDao().getById(run.runId))
            assertEquals("REJECTED:409", db.gatewayJobDao().submission(command)?.state)
            assertNull(retained.gatewayJobId)
            if (status == "CANCELED") {
                assertEquals("CANCELED", retained.status)
                assertEquals("Stopped by user", retained.terminalError)
            } else {
                assertEquals("BLOCKED", retained.status)
                assertTrue(retained.terminalError?.contains("EXECUTION_MODEL_LOADING; provider_admission; not_dispatched") == true)
                val failure = requireNotNull(db.gatewayJobDao().localMessage(run.assistantMessageId)?.timeline?.last()?.profileFailure)
                assertEquals(false, failure.dispatched)
                assertEquals(false, failure.requestReplayAllowed)
            }
            coVerify(exactly = 1) { client.observeCommand(config, command) }
        }
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun anotherAccountsNegativeReceiptCannotReleaseTheOriginalCommandFence() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, "original", turn.userMessage.id, run.assistantMessageId, run.runId, "model")
        val rejection = GatewayCommandRejection(1, "gateway", "other-owner", "original", "generation", "rejected", "queue_full", 429, "provider_admission", "not_dispatched", false)
        coEvery { client.observeCommand(config, "original") } throws GatewayCommandRejected(rejection)
        assertTrue(runCatching { repository.reconcileOrdinaryAdmissions(turn.chatRoom.id) }.isFailure)
        assertEquals("RESERVED_ORDINARY", db.gatewayJobDao().submission("original")?.state)
        assertNull(db.gatewayJobDao().submission("original")?.receipt)
    }

    @Test fun authenticatedAcceptedReceiptRepairsFailedAndParkedNoHeaderRunsForExistingReadOnlyRecovery() = runBlocking {
        val cases = listOf(
            Triple("FAILED", "GATEWAY_OUTCOME_UNKNOWN: response lost", "FAILED"),
            Triple("FAILED", "STREAM_INTERRUPTED: connection lost", "INTERRUPTED"),
            Triple("PARTIAL", "REMOTE_OUTCOME_UNKNOWN: reply lost", "PARTIAL"),
            Triple("BLOCKED", "GATEWAY_OUTCOME_UNKNOWN: GATEWAY_RECOVERY_EXPIRED awaiting reconnect", "BLOCKED")
        )
        for ((index, case) in cases.withIndex()) {
            val turn = turn()
            val run = turn.runs.single()
            val command = "failed-command-$index"
            repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, command, turn.userMessage.id, run.assistantMessageId, run.runId, "model")
            db.agentPersistenceDao().updateRunStatus(run.runId, case.first, 1, 2, case.second)
            coEvery { client.observeCommand(config, command) } returns GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = command, conversationId = "conversation-$index", jobKind = "generation", jobId = "saved-job-$index", profileId = "profile", model = "model")
            repository.reconcileOrdinaryAdmissions(turn.chatRoom.id)
            val recovered = requireNotNull(db.agentRunDao().getById(run.runId))
            assertEquals(case.third, recovered.status)
            assertEquals("saved-job-$index", recovered.gatewayJobId)
            assertTrue(dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery.canReadSavedResult(recovered))
            assertTrue(db.agentRunDao().getRecoverableGatewayRuns().any { it.runId == run.runId })
            assertEquals("", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        }
    }

    @Test fun authoritativeReceiptStillCannotResurrectCanceledRestoredOrAlreadyRejectedSavedResults() = runBlocking {
        val cases = listOf("CANCELED" to "Stopped", "INTERRUPTED" to "BACKUP_RESTORED", "EVIDENCE_MISSING" to "GATEWAY_OUTCOME_UNKNOWN: GATEWAY_SAVED_RESULT_UNVERIFIED")
        for ((index, case) in cases.withIndex()) {
            val turn = turn()
            val run = turn.runs.single()
            val command = "excluded-command-$index"
            repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, command, turn.userMessage.id, run.assistantMessageId, run.runId, "model")
            db.agentPersistenceDao().updateRunStatus(run.runId, case.first, 1, 2, case.second)
            coEvery { client.observeCommand(config, command) } returns GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = command, conversationId = "excluded-$index", jobKind = "generation", jobId = "excluded-job-$index", profileId = "profile", model = "model")
            repository.reconcileOrdinaryAdmissions(turn.chatRoom.id)
            assertEquals(case.first, db.agentRunDao().getById(run.runId)?.status)
            assertNull(db.agentRunDao().getById(run.runId)?.gatewayJobId)
            assertEquals(if (case.first == "CANCELED") mapOf(run.runId to turn.chatRoom.id) else emptyMap<String, Int>(), repository.unconfirmedOrdinaryStops(setOf(run.runId)))
        }
    }

    @Test fun stoppedTurnCannotBeReservedAfterSerializationAndBeforePost() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        db.agentPersistenceDao().updateRunStatus(run.runId, "CANCELED", 1, 2, "Stopped")
        assertTrue(runCatching { repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, "command", turn.userMessage.id, run.assistantMessageId, run.runId, "model") }.isFailure)
        assertNull(db.gatewayJobDao().submission("command"))
    }

    @Test fun originalOrdinaryStopRejectsAReplacedAccountOrInstallationBeforeCancelPost() = runBlocking {
        val turn = turn()
        val original = turn.runs.single()
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, original.runId, turn.userMessage.id, turn.assistantMessages.single().id, original.runId, "model")
        val run = original.copy(status = "CANCELED", gatewayJobId = "saved-job", gatewayBaseUrl = route.endpoint)
        db.agentRunDao().upsert(run)
        val profile = settings.fetchPlatformV2s().single()
        val gateway = mockk<GatewayAPI>()

        for (identity in listOf(GatewayScopeIdentity("replacement", "owner"), GatewayScopeIdentity("gateway", "another-account"))) {
            coEvery { client.identity(config) } returns identity
            val receipt = stopSavedGatewayRun(run, profile, gateway) { repository.ordinaryCancellationConfig(run) }
            assertEquals("GATEWAY_CANCELLATION_UNCONFIRMED", receipt.code)
        }

        coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
    }

    @Test fun originalOrdinaryStopUsesPairedIdentityBeforeAndAfterConversationAssociation() = runBlocking {
        val turn = turn()
        val original = turn.runs.single()
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, original.runId, turn.userMessage.id, turn.assistantMessages.single().id, original.runId, "model")
        val run = original.copy(status = "CANCELED", gatewayJobId = "saved-job", gatewayBaseUrl = route.endpoint)
        db.agentRunDao().upsert(run)
        val profile = settings.fetchPlatformV2s().single()
        val gateway = mockk<GatewayAPI>()
        coEvery { gateway.cancelJob("saved-job", any()) } returns GatewayCancelResult("saved-job", false, cancellationOutcome = "requested")

        assertEquals("GATEWAY_CANCELLATION_REQUESTED", stopSavedGatewayRun(run, profile, gateway) { repository.ordinaryCancellationConfig(run) }.code)
        associate(turn)
        coEvery { gateway.cancelJob("saved-job", any()) } returns GatewayCancelResult("saved-job", true, cancellationOutcome = "confirmed")
        assertEquals("GATEWAY_CANCELLATION_CONFIRMED", stopSavedGatewayRun(run, profile, gateway) { repository.ordinaryCancellationConfig(run) }.code)

        coVerify(exactly = 2) {
            gateway.cancelJob(
                "saved-job",
                match {
                    it.apiUrl == route.config.apiUrl &&
                        it.profileUid == "profile" &&
                        it.extraHeaders["X-Gateway-Installation-ID"] == "gateway" &&
                        it.extraHeaders["X-Gateway-Principal-ID"] == "owner"
                }
            )
        }
    }

    @Test fun malformedOrMissingCanonicalStopAuthorityNeverFallsBackToTheLegacyRoute() = runBlocking {
        val turn = turn()
        val original = turn.runs.single()
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, original.runId, turn.userMessage.id, turn.assistantMessages.single().id, original.runId, "model")
        val run = original.copy(status = "CANCELED", gatewayJobId = "saved-job", gatewayBaseUrl = route.endpoint)
        db.agentRunDao().upsert(run)
        associate(turn)
        val profile = settings.fetchPlatformV2s().single()
        val gateway = mockk<GatewayAPI>()
        val saved = requireNotNull(db.gatewayJobDao().submission(run.runId))
        db.gatewayJobDao().saveSubmission(saved.copy(payload = "{broken"))
        assertEquals("GATEWAY_CANCELLATION_UNCONFIRMED", stopSavedGatewayRun(run, profile, gateway) { repository.ordinaryCancellationConfig(run) }.code)
        db.openHelper.writableDatabase.execSQL("DELETE FROM gateway_submissions WHERE commandId=?", arrayOf(run.runId))
        assertEquals("GATEWAY_CANCELLATION_UNCONFIRMED", stopSavedGatewayRun(run, profile, gateway) { repository.ordinaryCancellationConfig(run) }.code)
        coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
    }

    @Test fun ordinaryStopRechecksCredentialsAfterItsIdentityRead() = runBlocking {
        val turn = turn()
        val original = turn.runs.single()
        repository.reserveOrdinaryTurn(turn.chatRoom.id, "profile", route.endpoint, original.runId, turn.userMessage.id, turn.assistantMessages.single().id, original.runId, "model")
        val run = original.copy(status = "CANCELED", gatewayJobId = "saved-job", gatewayBaseUrl = route.endpoint)
        db.agentRunDao().upsert(run)
        val profile = settings.fetchPlatformV2s().single()
        val gateway = mockk<GatewayAPI>()
        coEvery { client.identity(config) } coAnswers {
            coEvery { jobs.originalRoute("profile", route.endpoint) } returns route.copy(config = config.copy(token = "replacement"))
            GatewayScopeIdentity("gateway", "owner")
        }

        assertEquals("GATEWAY_CANCELLATION_UNCONFIRMED", stopSavedGatewayRun(run, profile, gateway) { repository.ordinaryCancellationConfig(run) }.code)
        coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
    }

    @Test fun boundedTranscriptPagesRetainIncompleteStateUntilTheFinalPageIsSaved() = runBlocking {
        var page = 0
        coEvery { client.messages(config, "conversation", any()) } answers {
            page++
            GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 20, messages = listOf(GatewayTranscriptMessage("message-$page", "turn-$page", "assistant", "Part $page", "complete", page.toLong())), hasMore = page < 11, nextCursor = "page-$page")
        }
        val initial = repository.openConversation("profile", "gateway", "owner", "conversation")
        assertTrue(db.gatewayJobDao().conversation(key)?.syncHasMore == true)
        assertTrue(!initial.historyComplete)
        assertEquals(10, messageCount(initial.chatId))
        repository.refreshChat(initial.chatId)
        assertTrue(db.gatewayJobDao().conversation(key)?.syncHasMore == false)
        assertEquals(11, messageCount(initial.chatId))
    }

    @Test fun lostInstructionReplyIsRetainedAndObserverReconcilesItWithoutPostingAgain() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(config.withGatewayIdentity(GatewayScopeIdentity("gateway", "owner")), "conversation", any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        assertEquals("PENDING_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        val receipt = GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = "instruction-command", conversationId = "conversation", jobKind = "engineering", jobId = "remote-job")
        coEvery { client.observeCommand(config, "instruction-command") } returns receipt
        repository.refreshPendingInstructions()
        assertEquals("ACCEPTED_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coEvery { client.command(config, "instruction-command") } returns receipt
        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")
        coVerify(exactly = 1) { client.observeCommand(config, "instruction-command") }
        coVerify(exactly = 1) { client.command(config, "instruction-command") }
        coVerify(exactly = 1) { commands.instruction(config.withGatewayIdentity(GatewayScopeIdentity("gateway", "owner")), "conversation", any()) }
    }

    @Test fun missingInstructionReceiptAfterRestartDoesNotAuthorizeAnExplicitReplay() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        val original = requireNotNull(db.gatewayJobDao().submission("instruction-command"))
        repository = GatewayConversationRepository(db, settings, client, jobs, commands)
        coEvery { client.command(config, "instruction-command") } returns null

        val failure = runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.exceptionOrNull()

        assertTrue(requireNotNull(failure).message.orEmpty().contains("unconfirmed"))
        val retained = requireNotNull(db.gatewayJobDao().submission("instruction-command"))
        assertEquals(original.payload, retained.payload)
        assertEquals(original.fingerprint, retained.fingerprint)
        assertEquals("PENDING_INSTRUCTION", retained.state)
        assertTrue(!retained.receipt.isNullOrBlank())
        coVerify(exactly = 1) { client.command(config, "instruction-command") }
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun acceptedInstructionRetainsItsReceiptWhenServerLookupIsMissing() = runBlocking {
        val record = instructionJob()
        val receipt = instructionReceipt()
        coEvery { commands.instruction(any(), any(), any()) } returns receipt
        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")
        repository = GatewayConversationRepository(db, settings, client, jobs, commands)
        coEvery { client.command(config, "instruction-command") } returns null

        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")

        assertEquals("ACCEPTED_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { client.command(config, "instruction-command") }
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun instructionRetryCanReconcileAfterItsJobFinishes() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        io.mockk.every { jobs.summary(record) } returns GatewayJobSummary("remote-job", "engineering", "conversation", state = "completed", acceptedAt = 1.0, profileId = "profile")
        coEvery { client.command(config, "instruction-command") } returns instructionReceipt()

        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")

        assertEquals("ACCEPTED_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun explicitInstructionReconciliationRetainsOnlyItsOriginalRejectionProof() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        val rejection = GatewayCommandRejection(1, "gateway", "different-owner", "instruction-command", "engineering", "rejected", "conversation_conflict", 409, "provider_admission", "not_dispatched", false)
        coEvery { client.command(config, "instruction-command") } throws GatewayCommandRejected(rejection)
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        assertEquals("PENDING_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coEvery { client.command(config, "instruction-command") } throws GatewayCommandRejected(rejection.copy(principalId = "owner"))

        val failure = runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.exceptionOrNull()

        assertTrue(failure is GatewayCommandRejected)
        assertEquals("REJECTED:409", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun newInstructionDoesNotFollowAStaleTileIntoAnotherActiveJob() = runBlocking {
        val record = instructionJob()
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(activeJobId = "replacement-job", jobState = "running")
        coEvery { commands.instruction(any(), any(), any()) } returns instructionReceipt().copy(jobId = "replacement-job")

        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)

        assertNull(db.gatewayJobDao().submission("instruction-command"))
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun instructionReceiptCannotChangeThePersistedJobDuringRetryOrObservation() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        val wrongJob = instructionReceipt().copy(jobId = "replacement-job")
        coEvery { client.command(config, "instruction-command") } returns wrongJob
        coEvery { client.observeCommand(config, "instruction-command") } returns wrongJob

        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        assertTrue(runCatching { repository.refreshPendingInstructions() }.isFailure)

        assertEquals("PENDING_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun existingInstructionCannotBeReconciledThroughAnotherJobInTheConversation() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        val replacement = record.copy(key = "replacement-key", remoteJobId = "replacement-job")
        db.gatewayJobDao().saveJobs(listOf(replacement))
        io.mockk.every { jobs.summary(replacement) } returns GatewayJobSummary("replacement-job", "engineering", "conversation", state = "running", acceptedAt = 2.0, profileId = "profile")
        coEvery { client.command(config, "instruction-command") } returns instructionReceipt()

        assertTrue(runCatching { repository.queueInstruction(replacement.key, "Inspect before editing", "instruction-command") }.isFailure)

        assertEquals("PENDING_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun malformedAcceptedInstructionReceiptsCannotSettleTheOriginalCommand() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        val receipt = instructionReceipt()
        for (invalid in listOf(receipt.copy(schemaVersion = 2), receipt.copy(commandId = "another-command"), receipt.copy(conversationId = "another-conversation"), receipt.copy(principalId = "another-owner"), receipt.copy(gatewayId = "another-gateway"), receipt.copy(jobKind = "generation"))) {
            coEvery { client.command(config, "instruction-command") } returns invalid
            assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
            assertEquals("PENDING_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        }
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun lateInstructionRejectionCannotDowngradeAnAcceptanceThatWonTheRace() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        coEvery { client.observeCommand(config, "instruction-command") } returns instructionReceipt()
        val rejection = GatewayCommandRejection(1, "gateway", "owner", "instruction-command", "engineering", "rejected", "conversation_conflict", 409, "provider_admission", "not_dispatched", false)
        coEvery { client.command(config, "instruction-command") } coAnswers {
            repository.refreshPendingInstructions()
            throw GatewayCommandRejected(rejection)
        }

        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")

        assertEquals("ACCEPTED_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun latePostConflictCannotOverwriteAnAcceptedInstructionReceipt() = runBlocking {
        val record = instructionJob()
        coEvery { client.observeCommand(config, "instruction-command") } returns instructionReceipt()
        coEvery { commands.instruction(any(), any(), any()) } coAnswers {
            repository.refreshPendingInstructions()
            throw GatewayConversationConflict()
        }

        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")

        assertEquals("ACCEPTED_INSTRUCTION", db.gatewayJobDao().submission("instruction-command")?.state)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    @Test fun legacyInstructionWithoutSavedJobMetadataStillReconcilesWithoutReplay() = runBlocking {
        val record = instructionJob()
        coEvery { commands.instruction(any(), any(), any()) } throws IOException("Receipt lost")
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        val pending = requireNotNull(db.gatewayJobDao().submission("instruction-command"))
        val legacyPayload = """{"command_id":"instruction-command","content":"Inspect before editing","expected_revision":2,"kind":"instruction"}"""
        db.gatewayJobDao().saveSubmission(pending.copy(payload = legacyPayload, fingerprint = gatewayIdentityKey(legacyPayload)))
        coEvery { client.command(config, "instruction-command") } returns null
        assertTrue(runCatching { repository.queueInstruction(record.key, "Inspect before editing", "instruction-command") }.isFailure)
        coEvery { client.command(config, "instruction-command") } returns instructionReceipt()

        repository.queueInstruction(record.key, "Inspect before editing", "instruction-command")

        val accepted = requireNotNull(db.gatewayJobDao().submission("instruction-command"))
        assertEquals("ACCEPTED_INSTRUCTION", accepted.state)
        assertEquals(legacyPayload, accepted.payload)
        coVerify(exactly = 1) { commands.instruction(any(), any(), any()) }
    }

    private suspend fun instructionJob(): GatewayJobRecord {
        val record = GatewayJobRecord("job-key", "gateway", "owner", "engineering", "remote-job", key, "profile", route.endpoint, "profile", "{}", 1)
        db.gatewayJobDao().saveJobs(listOf(record))
        io.mockk.every { jobs.summary(record) } returns GatewayJobSummary("remote-job", "engineering", "conversation", state = "running", acceptedAt = 1.0, profileId = "profile")
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(activeJobId = "remote-job", jobState = "running")
        return record
    }

    private fun instructionReceipt() = GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = "instruction-command", conversationId = "conversation", jobKind = "engineering", jobId = "remote-job")

    @Test fun localConversationIdentityReusesTheOrdinaryGatewayUuid() = runBlocking {
        val turn = turn()
        coEvery { settings.ensureConversationIdentity(turn.chatRoom.id, null) } returns "shared-stable-uuid"
        assertEquals("shared-stable-uuid", repository.clientConversationId(turn.chatRoom.id))
        coVerify(exactly = 1) { settings.ensureConversationIdentity(turn.chatRoom.id, null) }
    }

    private fun messageCount(chatId: Int): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages_v2 WHERE chat_id=?", arrayOf(chatId)).use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test fun multipartCrashReopensRoomAndPublishesOriginalAssistantOnlyAfterDigestCompletes() = runBlocking {
        db.close()
        val name = "gateway-parts-${UUID.randomUUID()}"
        files += name
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java, name).build()
        repository = GatewayConversationRepository(db, settings, client, jobs, commands)
        val turn = turn()
        val run = turn.runs.single()
        saveEngineering(turn)
        db.agentPersistenceDao().updateRunStatus(run.runId, "WAITING_REMOTE_JOB", 1, null, null)
        val content = "Verified answer αβ\n".repeat(20_000)
        val answer = GatewayTranscriptMessage("assistant", run.runId, "assistant", content, "completed", 2, 2.0, "${run.runId}:assistant")
        val parts = GatewayLogicalMessageAssembler.boundedParts(answer)
        assertTrue(parts.size >= 2)
        coEvery { client.messages(config, "conversation", null) } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, messages = listOf(transcript(run.runId).first(), parts.first()), hasMore = true, nextCursor = "part-cursor")
        coEvery { client.messages(config, "conversation", "part-cursor") } throws IOException("Process lost the response")
        assertTrue(runCatching { associate(turn) }.isFailure)
        assertEquals("part-cursor", db.gatewayJobDao().conversation(key)?.messageCursor)
        assertEquals("", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertTrue(db.replyPublicationDao().all().isEmpty())
        db.close()
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java, name).build()
        repository = GatewayConversationRepository(db, settings, client, jobs, commands)
        coEvery { client.messages(config, "conversation", "part-cursor") } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, messages = parts.drop(1), nextCursor = "completed-parts")
        coEvery { client.messages(config, "conversation", "completed-parts") } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, nextCursor = "completed-parts")
        repository.refreshChat(turn.chatRoom.id)
        repository.refreshChat(turn.chatRoom.id)
        assertEquals(content, db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals("COMPLETED", db.agentRunDao().getById(run.runId)?.status)
        assertEquals(1, db.replyPublicationDao().all().size)
        assertEquals(2, messageCount(turn.chatRoom.id))
    }

    @Test fun corruptLastPartRollsBackItsCursorAndCannotPublishPartialContent() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        saveEngineering(turn)
        db.agentPersistenceDao().updateRunStatus(run.runId, "WAITING_REMOTE_JOB", 1, null, null)
        val parts = GatewayLogicalMessageAssembler.boundedParts(GatewayTranscriptMessage("assistant", run.runId, "assistant", "x".repeat(300_000), "completed", 2, 2.0, "${run.runId}:assistant"))
        coEvery { client.messages(config, "conversation", null) } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, messages = listOf(parts.first()), hasMore = true, nextCursor = "first-part")
        coEvery { client.messages(config, "conversation", "first-part") } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, messages = listOf(parts.last().copy(content = "corrupt")), nextCursor = "bad-final")
        assertTrue(runCatching { associate(turn) }.isFailure)
        assertEquals("first-part", db.gatewayJobDao().conversation(key)?.messageCursor)
        assertEquals(1, db.gatewayJobDao().messagePartCount(key, "assistant", 2))
        assertEquals("", db.gatewayJobDao().localMessage(run.assistantMessageId)?.content)
        assertEquals("WAITING_REMOTE_JOB", db.agentRunDao().getById(run.runId)?.status)
        assertTrue(db.replyPublicationDao().all().isEmpty())
    }

    @Test fun knownRetentionGapStaysVisibleAfterLaterCompletePagesAndReadOnlyCacheNeedsNoRoom() = runBlocking {
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(profileId = null)
        coEvery { client.messages(config, "conversation", any()) } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, messages = transcript("remote"), nextCursor = "gap", historyComplete = false, historyLimitation = "Earlier turn expired", gap = true)
        assertEquals(key, repository.prepareTranscript("profile", "gateway", "owner", "conversation"))
        assertNull(db.gatewayJobDao().conversation(key)?.localChatId)
        val first = repository.transcriptPage(key)
        assertTrue(!first.historyComplete && first.historyLimitation == "Earlier turn expired")
        assertEquals("Saved answer", first.items.last().content)
        coEvery { client.messages(config, "conversation", "gap") } returns GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 2, nextCursor = "gap")
        repository.refreshTranscript(key)
        assertTrue(!repository.transcriptPage(key).historyComplete)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM chats_v2").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }

    @Test fun lostDerivedBranchAdmissionReusesOriginalTurnOnlyAfterExactSourceReceiptMatches() = runBlocking {
        val turn = turn()
        val run = turn.runs.single()
        val request = GatewayEngineeringRequest("owner/repo", "main", "main", "Implement", "model", chatId = turn.chatRoom.id, requestId = run.runId, profileId = "profile", taskScope = "implement", sourceBranch = "main", sourceSha = "a".repeat(40))
        val store = GatewayEngineeringStore(db, settings)
        store.add(GatewayEngineeringBinding("profile", route.endpoint, request, principalId = "owner", localUserMessageId = run.userMessageId, localAssistantMessageId = run.assistantMessageId, localRunId = run.runId, gatewayId = "gateway"))
        store.markAdmission(run.runId, "OUTCOME_UNKNOWN")
        val branch = requireNotNull(gatewayDerivedWorkingBranch(request))
        val receipt = GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = run.runId, conversationId = "conversation", jobKind = "engineering", jobId = "eng_" + "c".repeat(32), profileId = "profile", model = "model", repository = "owner/repo", branch = branch, base = "main", sourceBranch = "main", sourceSha = "a".repeat(40), workingBranch = branch)
        coEvery { client.command(config, run.runId) } returns receipt.copy(sourceSha = "b".repeat(40))
        assertTrue(runCatching { repository.openConversation("profile", "gateway", "owner", "conversation") }.isFailure)
        assertNull(db.gatewayJobDao().conversation(key))
        assertEquals("OUTCOME_UNKNOWN", db.gatewayJobDao().submission(run.runId)?.state)
        coEvery { client.command(config, run.runId) } returns receipt
        coEvery { client.conversation(config, "conversation") } returns snapshot.copy(repository = "owner/repo", branch = branch, base = "main", sourceBranch = "main", sourceSha = "a".repeat(40), workingBranch = branch)
        remote = transcript(run.runId)
        assertEquals(turn.chatRoom.id, repository.openConversation("profile", "gateway", "owner", "conversation").chatId)
        assertEquals(2, messageCount(turn.chatRoom.id))
        assertEquals("main", store.bindings().single().request.branch)
        assertEquals("a".repeat(40), store.bindings().single().request.sourceSha)
        assertEquals("ACCEPTED", db.gatewayJobDao().submission(run.runId)?.state)
        coVerify(exactly = 0) { commands.instruction(any(), any(), any()) }
    }
}
