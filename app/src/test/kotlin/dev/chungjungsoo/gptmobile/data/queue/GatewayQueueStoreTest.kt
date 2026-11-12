package dev.chungjungsoo.gptmobile.data.queue

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringBindingRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandReceipt
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRejected
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRejection
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import dev.chungjungsoo.gptmobile.data.github.GatewayJobClient
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRoute
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.GatewayOrdinaryReservation
import dev.chungjungsoo.gptmobile.data.github.GatewayScopeIdentity
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobResult
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySavedJob
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class GatewayQueueStoreTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var store: GatewayQueueStore
    private val settings = mockk<SettingRepository>()
    private val conversations = mockk<GatewayConversationRepository>()
    private val jobs = mockk<GatewayJobRepository>()
    private val client = mockk<GatewayJobClient>()
    private val gateway = mockk<GatewayAPI>()
    private val engineering = mockk<GatewayEngineeringClient>()
    private val profile = PlatformV2(uid = "gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.2:8090", model = "accepted-model")
    private val selection = GatewayQueuedSelection.capture(profile, profile.model)
    private val config = ProviderRequestConfig(profile.apiUrl, null, profileUid = profile.uid)
    private val route = GatewayJobRoute(profile.uid, profile.apiUrl, config)
    private var features = AppFeatureSettings()

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } answers { features }
        coEvery { jobs.originalRoute(profile.uid, profile.apiUrl) } returns route
        coEvery { conversations.reconcileOrdinaryAdmissions(any(), any(), any(), any()) } returns Unit
        store = newStore()
    }

    @After fun close() {
        database.close()
    }

    private fun newStore() = GatewayQueueStore(database, settings, conversations, jobs, client, gateway, engineering)

    private data class Prepared(val prompt: PendingPrompt, val request: PersistAgentTurnRequest)

    private suspend fun prepare(text: String = "Saved Gateway prompt"): Prepared {
        val initial = ChatRoomV2(title = text, enabledPlatform = listOf(profile.uid), conversationMode = ConversationMode.GATEWAY)
        val room = initial.copy(id = database.chatRoomDao().addChatRoom(initial).toInt())
        features = features.copy(conversationGateway = features.conversationGateway + (room.id to selection))
        val accepted = PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), gateway = selection)
        val prompt = PendingPrompt(UUID.randomUUID().toString(), room.id, text, Json.encodeToString(accepted), 0)
        database.pendingPromptDao().enqueue(prompt)
        return Prepared(
            requireNotNull(database.pendingPromptDao().get(prompt.id)),
            PersistAgentTurnRequest(
                room,
                MessageV2(chatId = room.id, content = text, platformType = null),
                listOf(AgentRunDraft(UUID.randomUUID().toString(), profile.uid, profile.compatibleType.name, profile.model)),
                mapOf(profile.uid to profile.model),
                queuedPromptId = prompt.id
            )
        )
    }

    private suspend fun claim(prepared: Prepared? = null, history: List<MessageV2> = emptyList()): Pair<PersistAgentTurnResult, GatewaySubmissionRecord> {
        val next = prepared ?: prepare()
        val result = requireNotNull(store.persist(next.prompt, history, next.request))
        return result to database.gatewayJobDao().queuedDispatches().single { it.runId == result.runs.single().runId }
    }

    private suspend fun reservation(lease: GatewaySubmissionRecord, command: String = "saved-command", state: String = "DISPATCHING_ORDINARY"): GatewaySubmissionRecord {
        val row = lease.copy(
            commandId = command,
            payload = Json.encodeToString(GatewayOrdinaryReservation("ordinary", profile.model, "installation", "principal")),
            state = state
        )
        database.gatewayJobDao().insertSubmission(row)
        return row
    }

    private fun receipt(command: String) = GatewayCommandReceipt(
        gatewayId = "installation",
        principalId = "principal",
        commandId = command,
        conversationId = "conversation",
        jobKind = "generation",
        jobId = "saved-job",
        profileId = profile.uid,
        model = profile.model
    )

    private fun rejection(command: String) = GatewayCommandRejection(1, "installation", "principal", command, "generation", "rejected", "EXECUTION_MODEL_LOADING", 409, "provider_admission", "not_dispatched", false)

    @Test fun twoChatsCompetingForTheQueueAtomicallyTransferOnlyOnePromptIntoARun() = runBlocking {
        val first = prepare("First prompt")
        val second = prepare("Second prompt")
        val results = listOf(first, second).map { prepared ->
            async(Dispatchers.IO) { store.persist(prepared.prompt, emptyList(), prepared.request) }
        }.awaitAll()
        val accepted = results.filterNotNull().single()
        assertEquals(1, database.gatewayJobDao().queuedDispatches().size)
        assertEquals(1, database.pendingPromptDao().observePending().first().size)
        assertEquals(accepted.userMessage.id, database.pendingPromptDao().get(if (results.first() != null) first.prompt.id else second.prompt.id)?.userMessageId)
        assertNotNull(database.gatewayJobDao().localMessage(accepted.assistantMessages.single().id))
    }

    @Test fun restartBeforeAnyRemoteCommandResumesTheSameRunAndAcceptedHistory() = runBlocking {
        val prepared = prepare()
        val earlier = MessageV2(id = 1000, chatId = prepared.prompt.chatId, content = "Frozen prior evidence", platformType = null)
        val (result, lease) = claim(prepared, listOf(earlier))
        val originalRun = result.runs.single()
        database.agentRunDao().upsert(originalRun.copy(status = "INTERRUPTED", startedAt = null, completedAt = 2, terminalError = "SERVICE_STOPPED"))
        val restarted = newStore()
        val request = requireNotNull(restarted.resumedRequest(lease, profile))
        assertEquals(originalRun.runId, request.runId)
        assertEquals(result.assistantMessages.single().id, request.assistantMessage.id)
        assertEquals(listOf("Frozen prior evidence", prepared.prompt.text), request.userMessages.map { it.content })
        assertEquals(originalRun.ownershipFence, database.agentRunDao().getById(originalRun.runId)?.ownershipFence)
        assertEquals("QUEUED", database.agentRunDao().getById(originalRun.runId)?.status)
        assertTrue(database.pendingPromptDao().observePending().first().isEmpty())
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun aStartedInterruptedRoundIsNeverBlindlyRestartedFromTheInitialPrompt() = runBlocking {
        val (result, lease) = claim()
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", startedAt = 1, completedAt = 2))
        assertNull(newStore().resumedRequest(lease, profile))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun cancellationAndMissingRemoteReceiptKeepTheGlobalLeaseAcrossRestart() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        database.agentRunDao().upsert(result.runs.single().copy(status = "CANCELED", completedAt = 2))
        coEvery { client.observeCommand(config, saved.commandId) } returns null
        assertFalse(newStore().remoteSettled(lease))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        val second = prepare("Later prompt")
        assertNull(store.persist(second.prompt, emptyList(), second.request))
        assertNull(database.pendingPromptDao().get(second.prompt.id)?.userMessageId)
        coVerify(exactly = 1) { client.observeCommand(config, saved.commandId) }
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun durableRejectedCommandReleasesTheQueueWithoutRequestingANonexistentJob() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        val outcome = rejection(saved.commandId)
        database.gatewayJobDao().saveSubmission(saved.copy(state = "REJECTED:409", receipt = Json.encodeToString(outcome)))
        database.agentRunDao().upsert(result.runs.single().copy(status = "BLOCKED", completedAt = 2, terminalError = "GATEWAY_COMMAND_REJECTED: EXECUTION_MODEL_LOADING; provider_admission; not_dispatched"))
        coEvery { client.observeCommand(config, saved.commandId) } throws GatewayCommandRejected(outcome)
        assertTrue(store.remoteSettled(lease))
        assertFalse(store.needsDelivery(lease))
        assertTrue(store.deliveryReady(lease))
        assertTrue(store.release(lease, "VERIFIED_REMOTE_TERMINAL", pendingDelivery = store.needsDelivery(lease)))
        assertTrue(database.gatewayJobDao().queuedDispatches().isEmpty())
        assertTrue(database.gatewayJobDao().queuedDeliveries().isEmpty())
        assertEquals("REJECTED:409", database.gatewayJobDao().submission(saved.commandId)?.state)
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun liveRejectionRetainsItsProofBeforeEndingTheLeaseAndRemovingTheDeliveryRequirement() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        val outcome = rejection(saved.commandId)
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2, terminalError = "GATEWAY_OUTCOME_UNKNOWN: response lost"))
        assertTrue(store.needsDelivery(lease))
        coEvery { client.observeCommand(config, saved.commandId) } throws GatewayCommandRejected(outcome)
        assertTrue(store.remoteSettled(lease))
        val retained = requireNotNull(database.gatewayJobDao().submission(saved.commandId))
        assertEquals("REJECTED:409", retained.state)
        assertEquals(outcome, Json.decodeFromString<GatewayCommandRejection>(requireNotNull(retained.receipt)))
        assertFalse(store.needsDelivery(lease))
        assertTrue(store.release(lease, "VERIFIED_REMOTE_TERMINAL", pendingDelivery = store.needsDelivery(lease)))
        assertTrue(store.active.first().isEmpty())
        coVerify(exactly = 1) { client.observeCommand(config, saved.commandId) }
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun rejectionLabelsWithoutExactAccountCommandKindAndNoDispatchProofKeepTheLease() = runBlocking {
        val (_, lease) = claim()
        val saved = reservation(lease)
        val valid = rejection(saved.commandId)
        val invalid = listOf(
            valid.copy(gatewayId = "another-installation"),
            valid.copy(principalId = "another-principal"),
            valid.copy(commandId = "another-command"),
            valid.copy(jobKind = "engineering"),
            valid.copy(schemaVersion = 2),
            valid.copy(httpStatus = 503),
            valid.copy(inferenceAttempted = true),
            valid.copy(dispatchState = "unknown")
        ).map { Json.encodeToString(it) } + listOf(null, "legacy HTTP rejection")
        coEvery { client.observeCommand(config, saved.commandId) } returns null
        for (payload in invalid) {
            database.gatewayJobDao().saveSubmission(saved.copy(state = "REJECTED:409", receipt = payload))
            assertFalse(store.remoteSettled(lease))
            assertTrue(store.needsDelivery(lease))
            assertFalse(store.deliveryReady(lease))
            assertFalse(store.cancelUnsubmitted(lease.chatId))
            assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun rejectionProofCannotReplaceTheFrozenQueueDestinationOrAnAcceptedCommand() = runBlocking {
        val (_, lease) = claim()
        val saved = reservation(lease)
        val outcome = rejection(saved.commandId)
        val rejected = saved.copy(state = "REJECTED:409", receipt = Json.encodeToString(outcome))
        val changed = listOf(
            rejected.copy(endpoint = "http://192.168.1.99:8090"),
            rejected.copy(connectionProfileUid = "another-profile"),
            rejected.copy(executionProfileUid = "another-profile"),
            rejected.copy(payload = Json.encodeToString(GatewayOrdinaryReservation("ordinary", "another-model", "installation", "principal")))
        )
        for (record in changed) {
            database.gatewayJobDao().saveSubmission(record)
            assertTrue(runCatching { store.remoteSettled(lease) }.isFailure)
            assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        }
        database.gatewayJobDao().saveSubmission(saved)
        coEvery { client.observeCommand(config, saved.commandId) } throws GatewayCommandRejected(outcome.copy(principalId = "another-principal"))
        assertTrue(runCatching { store.remoteSettled(lease) }.isFailure)
        assertEquals(saved, database.gatewayJobDao().submission(saved.commandId))
        val accepted = saved.copy(state = "ACCEPTED", receipt = Json.encodeToString(receipt(saved.commandId)))
        coEvery { client.observeCommand(config, saved.commandId) } coAnswers {
            database.gatewayJobDao().saveSubmission(accepted)
            throw GatewayCommandRejected(outcome)
        }
        assertFalse(store.remoteSettled(lease))
        assertEquals(accepted, database.gatewayJobDao().submission(saved.commandId))
        assertTrue(store.needsDelivery(lease))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun anExternalOrdinaryRejectionEndsOnlyItsOriginalUnknownAdmission() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2))
        coEvery { client.observeCommand(config, saved.commandId) } throws GatewayCommandRejected(rejection(saved.commandId))
        assertNull(store.externalWorkBlocker())
        assertEquals("REJECTED:409", database.gatewayJobDao().submission(saved.commandId)?.state)
        coVerify(exactly = 1) { client.observeCommand(config, saved.commandId) }
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun anExternalEngineeringRejectionMustMatchTheFrozenBindingBeforeTheFenceEnds() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease, state = "OUTCOME_UNKNOWN")
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2))
        val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Original task", profile.model, chatId = lease.chatId, requestId = saved.commandId, profileId = profile.uid)
        val binding = GatewayEngineeringBinding(profile.uid, profile.apiUrl, request, principalId = "principal", gatewayId = "installation")
        database.gatewayJobDao().saveBinding(GatewayEngineeringBindingRecord(saved.commandId, lease.chatId, null, Json.encodeToString(binding), 1))
        database.gatewayJobDao().saveSubmission(saved.copy(payload = Json.encodeToString(request)))
        val outcome = rejection(saved.commandId).copy(jobKind = "engineering")
        for (wrong in listOf(outcome.copy(principalId = "another-principal"), outcome.copy(jobKind = "generation"))) {
            coEvery { client.observeCommand(config, saved.commandId) } throws GatewayCommandRejected(wrong)
            assertTrue(runCatching { store.externalWorkBlocker() }.isFailure)
            assertEquals("OUTCOME_UNKNOWN", database.gatewayJobDao().submission(saved.commandId)?.state)
        }
        coEvery { client.observeCommand(config, saved.commandId) } throws GatewayCommandRejected(outcome)
        assertNull(store.externalWorkBlocker())
        assertEquals("REJECTED:409", database.gatewayJobDao().submission(saved.commandId)?.state)
        coVerify(exactly = 0) { engineering.get(any(), any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun aLaterRejectedCommandCannotEraseAnEarlierUnknownOutcomeOrAcceptedReply() = runBlocking {
        val (_, lease) = claim()
        val earlier = reservation(lease, command = "earlier-command")
        val later = reservation(lease, command = "later-command")
        database.gatewayJobDao().saveSubmission(later.copy(state = "REJECTED:409", receipt = Json.encodeToString(rejection(later.commandId))))
        coEvery { client.observeCommand(config, earlier.commandId) } returns null
        assertFalse(store.remoteSettled(lease))
        assertTrue(store.needsDelivery(lease))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        database.gatewayJobDao().saveSubmission(earlier.copy(state = "SETTLED", receipt = Json.encodeToString(receipt(earlier.commandId))))
        assertTrue(store.remoteSettled(lease))
        assertTrue(store.needsDelivery(lease))
        assertTrue(store.release(lease, "VERIFIED_REMOTE_TERMINAL", pendingDelivery = store.needsDelivery(lease)))
        assertEquals("LOCAL_QUEUE_DELIVERY_PENDING", store.active.first().single().state)
        assertFalse(store.deliveryReady(store.active.first().single()))
    }

    @Test fun rejectionProofCannotCertifyAnOlderJobWithoutItsAcceptedCommand() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        database.gatewayJobDao().saveSubmission(saved.copy(state = "REJECTED:409", receipt = Json.encodeToString(rejection(saved.commandId))))
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", gatewayJobId = "older-unknown-job", gatewayBaseUrl = profile.apiUrl, completedAt = 2))
        assertFalse(store.remoteSettled(lease))
        assertFalse(store.deliveryReady(lease))
        assertFalse(store.cancelUnsubmitted(lease.chatId))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
    }

    @Test fun terminalProofDoesNotDependOnTranscriptDeliveryAndAReleasedLeaseStaysReleased() = runBlocking {
        val (_, lease) = claim()
        val saved = reservation(lease)
        coEvery { client.observeCommand(config, saved.commandId) } returns receipt(saved.commandId)
        coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("saved-job", "completed", job = GatewaySavedJob(profile.model, "saved-job"))
        coEvery { conversations.refreshChat(lease.chatId) } throws IOException("transcript unavailable")
        assertTrue(store.remoteSettled(lease))
        coVerify(exactly = 0) { conversations.refreshChat(any()) }
        assertTrue(store.release(lease, "VERIFIED_REMOTE_TERMINAL"))
        assertTrue(runCatching { store.refresh(lease) }.isFailure)
        assertTrue(newStore().active.first().isEmpty())
        assertEquals("VERIFIED_REMOTE_TERMINAL", database.gatewayJobDao().submission(lease.commandId)?.receipt)
        assertNotNull(database.gatewayJobDao().submission(saved.commandId))
    }

    @Test fun terminalResultMustMatchTheOriginalAccountJobAndModel() = runBlocking {
        val (_, lease) = claim()
        val saved = reservation(lease)
        coEvery { client.observeCommand(config, saved.commandId) } returns receipt(saved.commandId).copy(principalId = "replacement-principal")
        assertTrue(runCatching { store.remoteSettled(lease) }.isFailure)
        coEvery { client.observeCommand(config, saved.commandId) } returns receipt(saved.commandId)
        coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("saved-job", "completed", job = GatewaySavedJob("different-model", "saved-job"))
        assertFalse(store.remoteSettled(lease))
        coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("another-job", "completed", job = GatewaySavedJob(profile.model, "another-job"))
        assertFalse(store.remoteSettled(lease))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun restoredBackupLeaseCannotAuthorizeAProviderAttempt() = runBlocking {
        val (_, lease) = claim()
        val request = requireNotNull(store.resumedRequest(lease, profile))
        val restored = lease.copy(receipt = "BACKUP_RESTORED")
        database.gatewayJobDao().saveSubmission(restored)
        assertTrue(requireNotNull(store.problem(restored)).contains("restored", ignoreCase = true))
        val authority = requireNotNull(store.guard(request, null))
        assertTrue(runCatching { authority.validate() }.isFailure)
        assertEquals(restored, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun changedProfileOrAttemptDestinationCannotUseTheAcceptedLease() = runBlocking {
        val (_, lease) = claim()
        val request = requireNotNull(store.resumedRequest(lease, profile))
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(token = "changed-credential"))
        assertTrue(requireNotNull(store.problem(lease)).contains("configuration changed"))
        assertTrue(runCatching { requireNotNull(store.guard(request, null)).validate() }.isFailure)
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        val rerouted = request.copy(platform = profile.copy(apiUrl = "http://192.168.1.99:8090"))
        assertTrue(runCatching { requireNotNull(store.guard(rerouted, null)).validate() }.isFailure)
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun deletingTheLocalChatCannotCascadeAwayAnUnconfirmedRemoteCommandOrLease() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        database.chatRoomDao().deleteChatRooms(result.chatRoom)
        assertNull(database.gatewayJobDao().localChat(result.chatRoom.id))
        assertEquals(lease, database.gatewayJobDao().queuedDispatches().single())
        assertEquals(saved, database.gatewayJobDao().submission(saved.commandId))
        coEvery { client.observeCommand(config, saved.commandId) } returns null
        assertFalse(store.remoteSettled(lease))
        assertTrue(requireNotNull(store.problem(lease)).contains("removed"))
    }

    @Test fun definitelyUnsentProofIsBoundToTheExactPendingCommandAndRun() = runBlocking {
        val (_, lease) = claim()
        val saved = reservation(lease, state = "RESERVED_ORDINARY")
        val dao = database.gatewayJobDao()
        assertEquals(0, dao.markOrdinaryNotDispatched("another-command", requireNotNull(lease.runId)))
        assertEquals(0, dao.markOrdinaryNotDispatched(saved.commandId, "another-run"))
        assertEquals("RESERVED_ORDINARY", dao.submission(saved.commandId)?.state)
        assertEquals(1, dao.markOrdinaryNotDispatched(saved.commandId, requireNotNull(lease.runId)))
        assertEquals("REJECTED_NOT_DISPATCHED", dao.submission(saved.commandId)?.state)
        assertTrue(store.remoteSettled(lease))
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun aLaterUnsentRetryCannotEraseAnEarlierPossibleDispatchOrALegacyUnknownOutcome() = runBlocking {
        val (result, lease) = claim()
        database.agentRunDao().upsert(result.runs.single().copy(status = "RUNNING", startedAt = 1))
        val dao = database.gatewayJobDao()
        val reserved = reservation(lease, command = "new-command", state = "RESERVED_ORDINARY")
        assertEquals(1, dao.markOrdinaryDispatching(reserved.commandId, requireNotNull(lease.runId)))
        assertEquals(0, dao.markOrdinaryNotDispatched(reserved.commandId, requireNotNull(lease.runId)))
        assertEquals("DISPATCHING_ORDINARY", dao.submission(reserved.commandId)?.state)
        val legacy = reservation(lease, command = "legacy-command", state = "PENDING_ORDINARY")
        assertEquals(0, dao.markOrdinaryNotDispatched(legacy.commandId, requireNotNull(lease.runId)))
        assertEquals("PENDING_ORDINARY", dao.submission(legacy.commandId)?.state)
        coEvery { client.observeCommand(config, any()) } returns null
        assertFalse(store.remoteSettled(lease))
        assertEquals(lease, dao.queuedDispatches().single())
    }

    @Test fun aLocallyCompletedRunDoesNotSettleAnUnknownOrdinaryGatewayCommand() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        database.agentRunDao().upsert(result.runs.single().copy(status = "COMPLETED", completedAt = 2))
        coEvery { client.observeCommand(config, saved.commandId) } returns null
        assertNotNull(store.externalWorkBlocker())
        assertEquals("DISPATCHING_ORDINARY", database.gatewayJobDao().submission(saved.commandId)?.state)
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun importedOrdinaryJobsUseTheirExactRemoteTerminalProofToReleaseTheExternalFence() = runBlocking {
        val snapshot = GatewayJobSummary(
            jobId = "saved-job",
            jobKind = "ordinary",
            model = profile.model,
            state = "RUNNING",
            acceptedAt = 1.0
        )
        val record = GatewayJobRecord(
            key = "saved-job-key",
            gatewayId = "installation",
            principalId = "principal",
            kind = "ordinary",
            remoteJobId = "saved-job",
            conversationKey = null,
            connectionProfileUid = profile.uid,
            endpoint = profile.apiUrl,
            executionProfileUid = profile.uid,
            snapshot = Json.encodeToString(snapshot),
            observedAtMillis = 1
        )
        database.gatewayJobDao().saveJobs(listOf(record))
        coEvery { client.identity(config) } returns GatewayScopeIdentity("installation", "principal")
        coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("saved-job", "completed", job = GatewaySavedJob(profile.model, "saved-job"))
        assertNull(store.externalWorkBlocker())
        val settled = Json.decodeFromString<GatewayJobSummary>(database.gatewayJobDao().cachedJobs().single().snapshot)
        assertEquals("completed", settled.state)
    }

    @Test fun onlyTheCurrentRunningMessageOwnerCanArmTheReservedCommand() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease, state = "RESERVED_ORDINARY")
        val run = result.runs.single()
        val dao = database.gatewayJobDao()
        assertEquals(0, dao.markOrdinaryDispatching(saved.commandId, run.runId))
        database.agentRunDao().upsert(run.copy(status = "RUNNING", startedAt = 1))
        database.agentPersistenceDao().updateMessage(result.assistantMessages.single().copy(currentRunId = "replacement-owner"))
        assertEquals(0, dao.markOrdinaryDispatching(saved.commandId, run.runId))
        assertEquals("RESERVED_ORDINARY", dao.submission(saved.commandId)?.state)
        database.agentPersistenceDao().updateMessage(result.assistantMessages.single())
        assertEquals(1, dao.markOrdinaryDispatching(saved.commandId, run.runId))
    }

    @Test fun explicitCancelReleasesOnlyAnUnsubmittedTurnAndPreservesPossibleRemoteWork() = runBlocking {
        val (unsubmitted, lease) = claim()
        assertTrue(store.cancelUnsubmitted(lease.chatId))
        assertEquals("CANCELED", database.agentRunDao().getById(unsubmitted.runs.single().runId)?.status)
        assertEquals("USER_CANCELLED_BEFORE_DISPATCH", database.gatewayJobDao().submission(lease.commandId)?.receipt)
        val (_, uncertain) = claim()
        reservation(uncertain)
        assertFalse(store.cancelUnsubmitted(uncertain.chatId))
        assertEquals(uncertain, database.gatewayJobDao().queuedDispatches().single())
    }

    @Test fun explicitCancelRecognizesAnExactRetainedRejectionWithoutDispatchingAgain() = runBlocking {
        val (result, lease) = claim()
        val saved = reservation(lease)
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2))
        database.gatewayJobDao().saveSubmission(saved.copy(state = "REJECTED:409", receipt = Json.encodeToString(rejection(saved.commandId))))
        assertTrue(store.cancelUnsubmitted(lease.chatId))
        assertTrue(store.active.first().isEmpty())
        assertEquals("CANCELED", database.agentRunDao().getById(requireNotNull(lease.runId))?.status)
        assertEquals("REJECTED:409", database.gatewayJobDao().submission(saved.commandId)?.state)
        coVerify(exactly = 0) { client.command(any(), any()) }
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
    }

    @Test fun explicitBackupReviewRequiresRemoteEvidenceAndPreservesTheSavedReplyDeliveryGate() = runBlocking {
        for (state in listOf("LOCAL_QUEUE_ACTIVE", "LOCAL_QUEUE_DELIVERY_PENDING")) {
            val (result, lease) = claim()
            val original = result.runs.single()
            database.agentRunDao().upsert(original.copy(status = "INTERRUPTED", startedAt = 1, completedAt = 2, terminalError = "BACKUP_RESTORED"))
            val restored = lease.copy(state = state, receipt = "BACKUP_RESTORED")
            database.gatewayJobDao().saveSubmission(restored)
            assertFalse(store.cancelUnsubmitted(restored.chatId))
            assertFalse(store.reviewRestored(restored))
            assertFalse(store.deliveryReady(restored))
            val saved = reservation(restored, command = "saved-$state")
            coEvery { client.observeCommand(config, saved.commandId) } returns null
            assertFalse(store.remoteSettled(restored))
            coEvery { client.command(config, saved.commandId) } returns receipt(saved.commandId)
            coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("saved-job", "completed", job = GatewaySavedJob(profile.model, "saved-job"))
            assertTrue(store.reviewRestored(restored))
            assertTrue(database.gatewayJobDao().queuedDispatches().isEmpty())
            val delivering = database.gatewayJobDao().queuedDeliveries(restored.chatId).single()
            assertEquals("RESTORED_REMOTE_TERMINAL_VERIFIED", delivering.receipt)
            val reviewed = requireNotNull(database.agentRunDao().getById(original.runId))
            assertEquals("INTERRUPTED", reviewed.status)
            assertEquals("saved-job", reviewed.gatewayJobId)
            assertEquals(original.userMessageId, reviewed.userMessageId)
            assertEquals(original.assistantMessageId, reviewed.assistantMessageId)
            assertEquals(original.ownershipFence, reviewed.ownershipFence)
            assertFalse(store.deliveryReady(delivering))
            assertNull(store.resumedRequest(delivering, profile))
            database.agentRunDao().upsert(reviewed.copy(status = "COMPLETED", terminalError = "GATEWAY_SAVED_RESULT_COMPLETED"))
            database.agentPersistenceDao().updateMessage(result.assistantMessages.single().copy(content = "Recovered saved reply"))
            assertTrue(store.deliveryReady(delivering))
            assertTrue(store.finishDelivery(delivering))
            assertTrue(store.active.first().isEmpty())
        }
    }

    @Test fun restoredRejectionsRequireFreshProofAndReleaseWithoutInventingSavedReplyDelivery() = runBlocking {
        for (state in listOf("LOCAL_QUEUE_ACTIVE", "LOCAL_QUEUE_DELIVERY_PENDING")) {
            val (result, lease) = claim()
            database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2, terminalError = "BACKUP_RESTORED"))
            val restored = lease.copy(state = state, receipt = "BACKUP_RESTORED")
            database.gatewayJobDao().saveSubmission(restored)
            val saved = reservation(restored, command = "rejected-$state")
            val outcome = rejection(saved.commandId)
            database.gatewayJobDao().saveSubmission(saved.copy(state = "REJECTED:409", receipt = Json.encodeToString(outcome)))
            assertFalse(store.deliveryReady(restored))
            assertFalse(store.cancelUnsubmitted(restored.chatId))
            coEvery { client.command(config, saved.commandId) } returns null
            assertFalse(store.reviewRestored(restored))
            coEvery { client.command(config, saved.commandId) } throws IOException("Original gateway unavailable")
            assertTrue(runCatching { store.reviewRestored(restored) }.isFailure)
            coEvery { client.command(config, saved.commandId) } throws GatewayCommandRejected(outcome.copy(principalId = "another-principal"))
            assertTrue(runCatching { store.reviewRestored(restored) }.isFailure)
            assertEquals(restored, database.gatewayJobDao().submission(restored.commandId))
            coEvery { client.command(config, saved.commandId) } throws GatewayCommandRejected(outcome)
            assertTrue(store.reviewRestored(restored))
            assertTrue(store.active.first().isEmpty())
            assertNull(database.agentRunDao().getById(requireNotNull(lease.runId))?.gatewayJobId)
            assertEquals("REJECTED:409", database.gatewayJobDao().submission(saved.commandId)?.state)
            coVerify(exactly = 4) { client.command(config, saved.commandId) }
        }
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
        coVerify(exactly = 0) { gateway.getJobResult(any(), any()) }
    }

    @Test fun aRestoredLaterRejectionCannotHideAnEarlierAcceptedSavedReply() = runBlocking {
        val (result, lease) = claim()
        val run = result.runs.single()
        database.agentRunDao().upsert(run.copy(status = "INTERRUPTED", gatewayJobId = "saved-job", gatewayBaseUrl = profile.apiUrl, completedAt = 2, terminalError = "BACKUP_RESTORED"))
        val restored = lease.copy(receipt = "BACKUP_RESTORED")
        database.gatewayJobDao().saveSubmission(restored)
        val accepted = reservation(restored, command = "earlier-accepted", state = "SETTLED")
        database.gatewayJobDao().saveSubmission(accepted.copy(receipt = Json.encodeToString(receipt(accepted.commandId))))
        val rejected = reservation(restored, command = "later-rejected")
        val outcome = rejection(rejected.commandId)
        database.gatewayJobDao().saveSubmission(rejected.copy(state = "REJECTED:409", receipt = Json.encodeToString(outcome)))
        coEvery { client.command(config, rejected.commandId) } throws GatewayCommandRejected(outcome)
        coEvery { client.command(config, accepted.commandId) } returns receipt(accepted.commandId)
        coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("saved-job", "completed", job = GatewaySavedJob(profile.model, "saved-job"))
        assertTrue(store.reviewRestored(restored))
        val delivering = store.active.first().single()
        assertEquals("LOCAL_QUEUE_DELIVERY_PENDING", delivering.state)
        assertEquals("RESTORED_REMOTE_TERMINAL_VERIFIED", delivering.receipt)
        assertEquals("saved-job", database.agentRunDao().getById(run.runId)?.gatewayJobId)
        assertTrue(store.needsDelivery(delivering))
        assertFalse(store.deliveryReady(delivering))
        coVerify(exactly = 1) { client.command(config, accepted.commandId) }
        coVerify(exactly = 1) { client.command(config, rejected.commandId) }
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
    }

    @Test fun aRestoredLocalUnsentMarkerAloneCannotReplaceRemoteReview() = runBlocking {
        val (result, lease) = claim()
        database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2, terminalError = "BACKUP_RESTORED"))
        val restored = lease.copy(receipt = "BACKUP_RESTORED")
        database.gatewayJobDao().saveSubmission(restored)
        val reserved = reservation(restored, state = "RESERVED_ORDINARY")
        coEvery { client.command(config, reserved.commandId) } returns null
        assertFalse(store.reviewRestored(restored))
        assertEquals(1, database.gatewayJobDao().markOrdinaryNotDispatched(reserved.commandId, requireNotNull(lease.runId)))
        assertFalse(store.reviewRestored(restored))
        assertEquals(restored, database.gatewayJobDao().queuedDispatches().single())
        coVerify(exactly = 2) { client.command(config, reserved.commandId) }
    }

    @Test fun aRestoredRejectionCannotCertifyAnotherCommandsStaleUnsentMarker() = runBlocking {
        for (state in listOf("RESERVED_ORDINARY", "REJECTED_NOT_DISPATCHED")) {
            val (result, lease) = claim()
            database.agentRunDao().upsert(result.runs.single().copy(status = "INTERRUPTED", completedAt = 2, terminalError = "BACKUP_RESTORED"))
            val restored = lease.copy(receipt = "BACKUP_RESTORED")
            database.gatewayJobDao().saveSubmission(restored)
            val rejected = reservation(restored, command = "earlier-rejected-$state")
            val outcome = rejection(rejected.commandId)
            database.gatewayJobDao().saveSubmission(rejected.copy(state = "REJECTED:409", receipt = Json.encodeToString(outcome)))
            val unsent = reservation(restored, command = "later-unsent-$state", state = state)
            coEvery { client.command(config, rejected.commandId) } throws GatewayCommandRejected(outcome)
            coEvery { client.command(config, unsent.commandId) } returns null
            assertFalse(store.remoteSettled(restored))
            assertFalse(store.reviewRestored(restored))
            assertEquals(state, database.gatewayJobDao().submission(unsent.commandId)?.state)
            assertEquals(restored, database.gatewayJobDao().queuedDispatches().single())
            coEvery { client.command(config, unsent.commandId) } returns receipt(unsent.commandId)
            coEvery { gateway.getJobResult("saved-job", config) } returns GatewayJobResult("saved-job", "running", job = GatewaySavedJob(profile.model, "saved-job"))
            assertFalse(store.reviewRestored(restored))
            assertEquals(state, database.gatewayJobDao().submission(unsent.commandId)?.state)
            assertEquals(restored, database.gatewayJobDao().queuedDispatches().single())
            coEvery { client.command(config, unsent.commandId) } throws GatewayCommandRejected(rejection(unsent.commandId))
            assertTrue(store.reviewRestored(restored))
            assertEquals("REJECTED:409", database.gatewayJobDao().submission(unsent.commandId)?.state)
            assertTrue(store.active.first().isEmpty())
            coVerify(exactly = 3) { client.command(config, unsent.commandId) }
        }
        coVerify(exactly = 0) { client.observeCommand(any(), any()) }
    }

    @Test fun terminalExecutionAllowsAnotherChatWhileTheSameChatWaitsForItsSavedReply() = runBlocking {
        val prepared = prepare()
        val (result, lease) = claim(prepared)
        val run = result.runs.single()
        database.agentRunDao().upsert(run.copy(status = "COMPLETED", completedAt = 2))
        assertTrue(store.release(lease, "VERIFIED_REMOTE_TERMINAL", pendingDelivery = true))
        assertTrue(database.gatewayJobDao().queuedDispatches().isEmpty())
        val delivering = store.active.first().single()
        assertEquals("LOCAL_QUEUE_DELIVERY_PENDING", delivering.state)
        assertFalse(store.deliveryReady(delivering))
        val next = prepared.prompt.copy(id = UUID.randomUUID().toString(), text = "Follow-up", userMessageId = null)
        database.pendingPromptDao().enqueue(next)
        val nextRequest = prepared.request.copy(
            chatRoom = result.chatRoom,
            userMessage = MessageV2(chatId = result.chatRoom.id, content = next.text, platformType = null),
            runs = listOf(AgentRunDraft(UUID.randomUUID().toString(), profile.uid, profile.compatibleType.name, profile.model)),
            queuedPromptId = next.id
        )
        assertNull(store.persist(next, emptyList(), nextRequest))
        val anotherChat = prepare("Independent chat")
        assertNotNull(store.persist(anotherChat.prompt, emptyList(), anotherChat.request))
        database.agentPersistenceDao().updateMessage(result.assistantMessages.single().copy(content = "Recovered answer"))
        assertTrue(store.deliveryReady(delivering))
        assertTrue(store.finishDelivery(delivering))
        assertTrue(database.gatewayJobDao().queuedDeliveries(result.chatRoom.id).isEmpty())
        assertNull(database.pendingPromptDao().get(next.id)?.userMessageId)
    }
}
