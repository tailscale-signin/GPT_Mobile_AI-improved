package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayEngineeringSubmissionTest {
    private val context = mockk<Context>()
    private val workManager = mockk<WorkManager>()
    private val settings = mockk<SettingRepository>()
    private val client = mockk<GatewayEngineeringClient>()
    private val store = mockk<GatewayEngineeringStore>()
    private val conversations = mockk<GatewayConversationRepository>()
    private val history = mockk<GatewayJobClient>()
    private val modelPreparation = mockk<GatewayModelPreparationRepository>()
    private val selected = PlatformV2(uid = "execution-profile", name = "Selected inference", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.8:8080/v1", model = "profile-default")
    private val originalGateway = PlatformV2(uid = "original-gateway", name = "Original gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.9:8090/v1", model = "unused")
    private val capability = GatewayEngineeringCapabilities("engineering-gateway", 1, enabled = true, samplingPolicyProtocol = 1, principalId = "owner", gatewayId = "gateway", taskScopeProtocol = 1, taskScopes = listOf("read", "plan", "implement"), conversationSync = true, durableDetach = true, durationHours = listOf(3, 6, 12, 24, 0), executionBudgetLimits = GatewayEngineeringBudget(512, 8_388_608, 256, 256, 900))
    private fun repository() = GatewayEngineeringRepository(context, settings, client, mockk(), store, conversations, history, modelPreparation)

    @Before fun admissionDefaults() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(context) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow(GatewayEngineeringRepository.OBSERVER_WORK_NAME) } returns flowOf(emptyList())
        every { workManager.enqueueUniquePeriodicWork(GatewayEngineeringRepository.OBSERVER_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, any()) } returns mockk()
        coEvery { store.bindings(8) } returns emptyList()
        coEvery { store.hasMigratedAdmission(any(), any()) } returns false
        coEvery { store.commandLookupDue(any()) } returns true
        coEvery { store.deferCommandLookup(any()) } returns Unit
        coEvery { modelPreparation.prepareSelected(any(), any(), any(), any()) } returns null
        coEvery { history.identity(any()) } returns GatewayScopeIdentity("gateway", "owner")
        coEvery { conversations.requireSavedSelection(any()) } returns Unit
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf(selected.uid to GitHubChatProfileOverride(githubTools = true)))
    }

    @After fun restoreWorkManager() {
        unmockkObject(WorkManager.Companion)
    }

    @Test fun importedConversationUsesFrozenServerDestinationAndModelWithoutPhoneWorkspace() = runTest {
        val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "owner", conversationId = "canonical", profileId = selected.uid, model = "original-chat-model", repository = "original-owner/original-repository", branch = "original-work", base = "default-branch", revision = 42, sourceBranch = "original-source", sourceSha = "a".repeat(40), workingBranch = "original-work")
        val local = GatewayConversationRecord("key", "gateway", "owner", "canonical", originalGateway.uid, originalGateway.apiUrl, selected.uid, 8, Json.encodeToString(snapshot))
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf(selected.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = "different-current-gateway", githubTools = true)))
        coEvery { conversations.boundSnapshot(8, selected.uid, "original-chat-model") } returns (local to snapshot)
        coEvery { conversations.clientConversationId(8) } returns "local-stable-identity"
        coEvery { store.bindings(null) } returns emptyList()
        coEvery { client.capabilities(any()) } returns capability
        coEvery { client.prepareBinding(any(), any()) } returns GatewayExecutionAdmission(JsonObject(mapOf("binding_id" to JsonPrimitive("bind_" + "a".repeat(32)))), JsonObject(emptyMap()))
        val saved = slot<GatewayEngineeringBinding>()
        coEvery { store.add(capture(saved)) } throws IOException("Stop fixture after durable checkpoint")
        assertTrue(runCatching { repository().submitBoundTurn(8, selected.uid, "original-chat-model", "Plan the next task", "command", 11, 12, "run", "plan") }.isFailure)
        assertEquals(originalGateway.uid, saved.captured.profileUid)
        assertEquals(originalGateway.apiUrl, saved.captured.endpoint)
        coVerify(exactly = 1) { modelPreparation.prepareSelected(originalGateway.copy(model = "original-chat-model"), any(), true, any()) }
        assertEquals("original-owner/original-repository", saved.captured.request.repository)
        assertEquals("original-work", saved.captured.request.branch)
        assertEquals("default-branch", saved.captured.request.base)
        assertEquals("original-source", saved.captured.request.sourceBranch)
        assertEquals("a".repeat(40), saved.captured.request.sourceSha)
        assertEquals("original-chat-model", saved.captured.request.model)
        assertEquals("original-chat-model", saved.captured.executionSelection?.requestedModelId)
        assertEquals("canonical", saved.captured.request.conversationId)
        assertEquals(42L, saved.captured.request.expectedRevision)
        assertEquals(11, saved.captured.localUserMessageId)
        assertEquals(12, saved.captured.localAssistantMessageId)
        assertEquals(GitHubSamplingSettings(), saved.captured.request.samplingPolicy)
        assertEquals(24, saved.captured.request.durationHours)
        assertEquals(512, saved.captured.request.executionBudget?.modelCalls)
        assertEquals(2_097_152, saved.captured.request.executionBudget?.modelTokens)
        assertTrue(saved.captured.budgetSelection?.limitedByHost == false)
        coVerify(exactly = 0) { client.create(any(), any()) }
    }

    @Test fun capabilitySupportDoesNotDisappearWhenTheGatewayIsTemporarilyUnavailable() = runTest {
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf(selected.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = originalGateway.uid)))
        val config = originalGateway.engineeringConfig()
        coEvery { client.capabilities(config) } returns capability.copy(enabled = false, readiness = GatewayEngineeringReadiness(1, ready = false))
        assertTrue(repository().supportsDurableChat(selected.uid))
        coEvery { client.capabilities(config) } throws UnsupportedEngineeringGateway()
        assertFalse(repository().supportsDurableChat(selected.uid))
        val unavailable = IOException("Gateway unavailable")
        coEvery { client.capabilities(config) } throws unavailable
        val outcome = runCatching { repository().supportsDurableChat(selected.uid) }
        assertTrue(outcome.isFailure)
        assertSame(unavailable, outcome.exceptionOrNull())
        coVerify(exactly = 3) { client.capabilities(config) }
        coVerify(exactly = 0) {
            client.capabilities(selected.engineeringConfig())
            client.prepareBinding(any(), any())
            client.create(any(), any())
            client.control(any(), any(), any())
        }
    }

    @Test fun explicitCommitProhibitionBlocksCommittingScopeWithoutPreventingReadOrPlan() {
        for (request in listOf("Fix it but do not commit", "Implement without commits", "Fix the parser without committing", "Change it; never push", "Update this, don't commit or push")) {
            assertTrue(runCatching { requireGatewayCommitPermission(request, "implement") }.isFailure)
            requireGatewayCommitPermission(request, "read")
            requireGatewayCommitPermission(request, "plan")
        }
        requireGatewayCommitPermission("Implement and verify the feature", "implement")
    }

    @Test fun boundConversationCommitProhibitionRejectsBeforeAdmissionOrSubmission() = runTest {
        val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "owner", conversationId = "canonical", profileId = selected.uid, model = selected.model, repository = "owner/repo", branch = "work", base = "main", revision = 42)
        val local = GatewayConversationRecord("key", "gateway", "owner", "canonical", originalGateway.uid, originalGateway.apiUrl, selected.uid, 8, Json.encodeToString(snapshot))
        coEvery { conversations.boundSnapshot(8, selected.uid, selected.model) } returns (local to snapshot)
        coEvery { store.bindings(null) } returns emptyList()
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { client.capabilities(any()) } returns capability
        val failure = runCatching {
            repository().submitBoundTurn(8, selected.uid, selected.model, "Fix the parser without committing", "command", 11, 12, "run", "implement")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("explicitly forbids") == true)
        coVerify(exactly = 1) { conversations.boundSnapshot(8, selected.uid, selected.model) }
        coVerify(exactly = 0) {
            client.capabilities(any())
            client.prepareBinding(any(), any())
            store.add(any())
            store.markAdmission(any(), any())
            client.create(any(), any())
            client.control(any(), any(), any())
        }
    }

    @Test fun rejectedFirstAdmissionIsNotObservedAsUnconfirmedRunningWork() = runTest {
        rejectedAdmission("PENDING", "REJECTED:409", expectedUnknown = false)
    }

    @Test fun blockedExactModelPreparationLeavesTheDraftBeforeAnyEngineeringAdmission() = runTest {
        val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "owner", conversationId = "canonical", profileId = selected.uid, model = selected.model, repository = "owner/repo", branch = "work", base = "main", revision = 42)
        val local = GatewayConversationRecord("key", "gateway", "owner", "canonical", originalGateway.uid, originalGateway.apiUrl, selected.uid, 8, Json.encodeToString(snapshot))
        coEvery { conversations.boundSnapshot(8, selected.uid, selected.model) } returns (local to snapshot)
        coEvery { store.bindings(null) } returns emptyList()
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { client.capabilities(originalGateway.engineeringConfig()) } returns capability
        coEvery { modelPreparation.prepareSelected(any(), any(), any(), any()) } returns GatewayModelPreparationState(originalGateway.uid, selected.model, GatewayModelPreparationPhase.BLOCKED, "The selected model is unavailable")
        val failure = runCatching { repository().submitBoundTurn(8, selected.uid, selected.model, "Inspect the parser", "command", 11, 12, "run", "read") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("selected model") == true)
        coVerify(exactly = 1) { modelPreparation.prepareSelected(originalGateway.copy(model = selected.model), any(), true, any()) }
        coVerify(exactly = 0) {
            client.prepareBinding(any(), any())
            client.preflight(any(), any())
            store.add(any())
            client.create(any(), any())
        }
    }

    @Test fun changedSavedGatewayIdentityBlocksBeforeModelPreparation() = runTest {
        val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "owner", conversationId = "canonical", profileId = selected.uid, model = selected.model, repository = "owner/repo", branch = "work", base = "main", revision = 42)
        val local = GatewayConversationRecord("key", "gateway", "owner", "canonical", originalGateway.uid, originalGateway.apiUrl, selected.uid, 8, Json.encodeToString(snapshot))
        coEvery { conversations.boundSnapshot(8, selected.uid, selected.model) } returns (local to snapshot)
        coEvery { store.bindings(null) } returns emptyList()
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { client.capabilities(originalGateway.engineeringConfig()) } returns capability
        coEvery { history.identity(any()) } returns GatewayScopeIdentity("replacement", "owner")

        val failure = runCatching { repository().submitBoundTurn(8, selected.uid, selected.model, "Inspect the parser", "command", 11, 12, "run", "read") }.exceptionOrNull()

        assertTrue(failure?.message?.contains("original gateway or account changed") == true)
        coVerify(exactly = 0) {
            modelPreparation.prepareSelected(any(), any(), any(), any())
            client.prepareBinding(any(), any())
            store.add(any())
            client.create(any(), any())
        }
    }

    @Test fun changedSavedBackendBlocksBeforeModelPreparationOrNewBindingAdmission() = runTest {
        val retained = savedExecutionConversation()
        val rejected = GatewayExecutionRejected("EXECUTION_CONFIGURATION_CHANGED", 409, retryable = false)
        coEvery { client.bindingCapabilities(any(), retained.executionSelection!!, retained.executionAdmission!!.bindingId) } throws rejected

        val failure = runCatching { submitSavedExecutionTurn() }.exceptionOrNull()

        assertSame(rejected, failure)
        coVerify(exactly = 1) {
            client.bindingCapabilities(originalGateway.engineeringConfig().withGatewayIdentity(GatewayScopeIdentity("gateway", "owner")), retained.executionSelection!!, retained.executionAdmission!!.bindingId)
        }
        coVerify(exactly = 0) {
            modelPreparation.prepareSelected(any(), any(), any(), any())
            client.prepareBinding(any(), any())
            client.preflight(any(), any())
            store.add(any())
            client.create(any(), any())
        }
    }

    @Test fun changedSavedSelectionBlocksBeforeReadinessOrModelPreparation() = runTest {
        savedExecutionConversation()
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected.copy(apiUrl = "http://192.168.1.10:8080/v1"), originalGateway)

        val failure = runCatching { submitSavedExecutionTurn() }.exceptionOrNull()

        assertTrue(failure?.message?.contains("selected execution identity") == true)
        coVerify(exactly = 0) {
            client.bindingCapabilities(any(), any(), any())
            modelPreparation.prepareSelected(any(), any(), any(), any())
            client.prepareBinding(any(), any())
            store.add(any())
            client.create(any(), any())
        }
    }

    @Test fun sleepingSavedModelIsPreparedOnlyAfterItsOriginalBackendIsVerified() = runTest {
        preparesRetainedBinding("EXECUTION_MODEL_NOT_LOADED")
    }

    @Test fun loadingSavedModelReconcilesPreparationAndRetainsItsOriginalBinding() = runTest {
        preparesRetainedBinding("EXECUTION_MODEL_LOADING")
    }

    @Test fun readySavedModelStillRetainsItsOriginalBindingWithoutCreatingAnother() = runTest {
        preparesRetainedBinding(null)
    }

    @Test fun unverifiedReadinessAndOtherBindingFailuresCannotAuthorizeModelPreparation() = runTest {
        val retained = savedExecutionConversation()
        val failures = listOf(
            GatewayExecutionRejected("EXECUTION_MODEL_NOT_LOADED", 503),
            GatewayExecutionRejected("EXECUTION_MODEL_LOADING", 503, retryable = false),
            GatewayExecutionRejected("EXECUTION_MODEL_NOT_LOADED", 503, retryable = true, inferenceAttempted = true),
            GatewayExecutionRejected("EXECUTION_MODEL_UNAVAILABLE", 503, retryable = true),
            GatewayAdmissionException(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, null, null, GatewayAdmissionSurface.CAPABILITIES),
            CancellationException("The submitting observer detached")
        )
        for (rejected in failures) {
            coEvery { client.bindingCapabilities(any(), retained.executionSelection!!, retained.executionAdmission!!.bindingId) } throws rejected
            assertSame(rejected, runCatching { submitSavedExecutionTurn() }.exceptionOrNull())
        }
        coVerify(exactly = failures.size) { client.bindingCapabilities(any(), any(), any()) }
        coVerify(exactly = 0) {
            modelPreparation.prepareSelected(any(), any(), any(), any())
            client.prepareBinding(any(), any())
            store.add(any())
            client.create(any(), any())
        }
    }

    @Test fun backendChangingDuringPreparationRejectsBeforeSavingOrCreatingTheTurn() = runTest {
        val retained = savedExecutionConversation()
        val events = mutableListOf<String>()
        val rejected = GatewayExecutionRejected("EXECUTION_CONFIGURATION_CHANGED", 409, retryable = false)
        var reads = 0
        coEvery { client.bindingCapabilities(any(), retained.executionSelection!!, retained.executionAdmission!!.bindingId) } answers {
            events += "verify-original"
            if (++reads == 1) retained.executionAdmission!! else throw rejected
        }
        coEvery { modelPreparation.prepareSelected(any(), any(), any(), any()) } answers {
            events += "prepare-selected"
            readyPreparation()
        }

        assertSame(rejected, runCatching { submitSavedExecutionTurn() }.exceptionOrNull())
        assertEquals(listOf("verify-original", "prepare-selected", "verify-original"), events)
        coVerify(exactly = 0) {
            client.prepareBinding(any(), any())
            client.preflight(any(), any())
            store.add(any())
            client.create(any(), any())
        }
    }

    private fun savedExecutionConversation(): GatewayEngineeringBinding {
        val retained = retainedBinding()
        val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "owner", conversationId = "canonical", profileId = selected.uid, model = selected.model, repository = "owner/repo", branch = "work", base = "main", revision = 42, executionBinding = retained.executionAdmission!!.binding)
        val local = GatewayConversationRecord("key", "gateway", "owner", "canonical", originalGateway.uid, originalGateway.apiUrl, selected.uid, 8, Json.encodeToString(snapshot))
        coEvery { conversations.boundSnapshot(8, selected.uid, selected.model) } returns (local to snapshot)
        coEvery { conversations.clientConversationId(8) } returns "local-stable-identity"
        coEvery { store.bindings(null) } returns emptyList()
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { client.capabilities(originalGateway.engineeringConfig()) } returns capability
        return retained
    }

    private suspend fun submitSavedExecutionTurn(): GatewayEngineeringBinding? =
        repository().submitBoundTurn(8, selected.uid, selected.model, "Inspect the parser", "new-command", 11, 12, "run", "read")

    private fun readyPreparation(): GatewayModelPreparationState = GatewayModelPreparationState(
        originalGateway.uid,
        selected.model,
        GatewayModelPreparationPhase.READY,
        "Ready",
        ReasoningCapabilityObservation(originalGateway.uid, selected.model, ReasoningMode.OFF, ReasoningCapabilityStatus.SUPPORTED, "configuration", "runtime", 1L, null, routeVerified = true, supportVerified = true)
    )

    private suspend fun preparesRetainedBinding(readinessCode: String?) {
        val retained = savedExecutionConversation()
        val events = mutableListOf<String>()
        var reads = 0
        coEvery { client.bindingCapabilities(any(), retained.executionSelection!!, retained.executionAdmission!!.bindingId) } answers {
            events += "verify-original"
            if (++reads == 1 && readinessCode != null) throw GatewayExecutionRejected(readinessCode, 503, retryable = true)
            retained.executionAdmission!!
        }
        coEvery { modelPreparation.prepareSelected(any(), any(), any(), any()) } answers {
            events += "prepare-selected"
            readyPreparation()
        }
        val saved = slot<GatewayEngineeringBinding>()
        val checkpoint = IOException("Stop fixture after durable checkpoint")
        coEvery { store.add(capture(saved)) } answers {
            events += "save-command"
            throw checkpoint
        }

        assertSame(checkpoint, runCatching { submitSavedExecutionTurn() }.exceptionOrNull())
        assertEquals(listOf("verify-original", "prepare-selected", "verify-original", "save-command"), events)
        assertEquals(retained.executionAdmission!!.bindingId, saved.captured.request.executionBindingId)
        assertEquals(retained.executionAdmission, saved.captured.executionAdmission)
        assertEquals(retained.executionSelection, saved.captured.executionSelection)
        coVerify(exactly = 1) { modelPreparation.prepareSelected(originalGateway.copy(model = selected.model), any(), true, any()) }
        coVerify(exactly = 0) {
            client.prepareBinding(any(), any())
            client.create(any(), any())
        }
    }

    @Test fun repeatedMissingReceiptsAfterDispatchNeverReplayEvenOnExplicitReconnect() = runTest {
        val pending = GatewayEngineeringBinding(originalGateway.uid, originalGateway.apiUrl, GatewayEngineeringRequest("owner/repo", "work", "main", "Original task", selected.model, chatId = 8, requestId = "original-command", profileId = selected.uid), principalId = "owner", gatewayId = "gateway")
        coEvery { store.bindings(null) } returns listOf(pending)
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { client.principal(any(), requireReady = false) } returns "owner"
        coEvery { client.capabilities(any()) } returns capability
        coEvery { history.command(any(), "original-command") } returns null
        // Explicit reconnect bypasses a parked lookup, but still cannot POST.
        coEvery { store.commandLookupDue("original-command") } returns false
        for (state in listOf("DISPATCHING", "OUTCOME_UNKNOWN", "MIGRATED_REVIEW")) {
            coEvery { store.admissionState("original-command") } returns state
            repeat(3) { assertEquals(pending, repository().reconnect("original-command")) }
        }
        coVerify(exactly = 9) { history.command(any(), "original-command") }
        coVerify(exactly = 9) { store.deferCommandLookup("original-command") }
        verify(exactly = 9) { workManager.enqueueUniquePeriodicWork(GatewayEngineeringRepository.OBSERVER_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, any()) }
        coVerify(exactly = 0) {
            client.bindingCapabilities(any(), any(), any())
            client.create(any(), any())
            client.control(any(), any(), any())
            store.markAdmission(any(), any())
        }
    }

    @Test fun originalSelectionChangingAfterPreflightRejectsBeforeAnyCommandPost() = runTest {
        rejectedAdmission("PENDING", "REJECTED:403", expectedUnknown = false, selectionChanged = true)
    }

    @Test fun unknownAdmissionBlocksAnotherTurnUsingReadOnlyObservation() = runTest {
        val pending = GatewayEngineeringBinding(originalGateway.uid, originalGateway.apiUrl, GatewayEngineeringRequest("owner/repo", "work", "main", "Earlier request", selected.model, chatId = 8, requestId = "original-command", profileId = selected.uid), principalId = "owner")
        coEvery { store.bindings(8) } returns listOf(pending)
        coEvery { store.bindings(null) } returns listOf(pending)
        coEvery { store.admissionState("original-command") } returns "OUTCOME_UNKNOWN"
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { client.principal(any(), requireReady = false) } returns "owner"
        coEvery { client.capabilities(any()) } returns capability
        coEvery { history.observeCommand(any(), "original-command") } returns null
        val repository = repository()
        assertTrue(repository.hasUnconfirmedAdmission(8, selected.uid))
        assertTrue(runCatching { repository.requireNoUnconfirmedAdmission(8, selected.uid, "new-command") }.isFailure)
        coVerify(exactly = 1) { history.observeCommand(any(), "original-command") }
        coVerify(exactly = 0) { client.create(any(), any()) }
    }

    private fun retainedBinding(): GatewayEngineeringBinding {
        val selection = executionSelection(selected, null, "saved-privacy-scope")
        val bindingJson = buildJsonObject {
            put("binding_id", "bind_" + "a".repeat(32))
            put("routing_policy", "SELECTED_ONLY")
            put("profile_uid", selection.profileUid)
            put("profile_revision", selection.profileRevision)
            put("provider_connection_id", selection.providerConnectionId)
            put("provider_connection_revision", selection.providerConnectionRevision)
            put("provider_kind", selection.providerKind)
            put("requested_model_id", selection.requestedModelId)
            put("privacy_scope", selection.privacyScope)
            put("tool_owner", selection.toolOwner)
            put("configured_endpoint_identity", selectionDigest(JsonPrimitive(selection.configuredEndpoint)))
            put("generation_parameters", selection.generationParameters)
            put("gateway_installation_id", "gateway")
            put("backend_endpoint_identity", "a".repeat(64))
            put("backend_connection_id", "original-backend")
            put("runtime_config_revision", "b".repeat(64))
        }
        val admission = GatewayExecutionAdmission(
            bindingJson,
            buildJsonObject {
                put("requested_model_id", selection.requestedModelId)
                put("reported_model_id", selection.requestedModelId)
            }
        )
        return GatewayEngineeringBinding(originalGateway.uid, originalGateway.apiUrl, GatewayEngineeringRequest("owner/repo", "work", "main", "Inspect", selected.model, chatId = 8, requestId = "command", profileId = selected.uid, executionBindingId = admission.bindingId, taskScope = "read"), principalId = "owner", executionSelection = selection, executionAdmission = admission, gatewayId = "gateway")
    }

    private suspend fun rejectedAdmission(initial: String, expectedState: String, expectedUnknown: Boolean, selectionChanged: Boolean = false, bindingRejected: Boolean = false) {
        val binding = retainedBinding()
        val selection = requireNotNull(binding.executionSelection)
        val admission = requireNotNull(binding.executionAdmission)
        var admissionState = initial
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { store.bindings(null) } returns listOf(binding)
        coEvery { store.admissionState("command") } answers { admissionState }
        coEvery { store.markAdmission("command", any()) } answers { admissionState = secondArg() }
        coEvery { client.principal(any(), requireReady = false) } returns "owner"
        coEvery { client.capabilities(any()) } returns capability
        coEvery { history.command(any(), "command") } returns null
        coEvery { history.observeCommand(any(), "command") } returns null
        coEvery { client.bindingCapabilities(any(), selection, admission.bindingId) } returns admission
        if (bindingRejected) coEvery { client.bindingCapabilities(any(), selection, admission.bindingId) } throws GatewayExecutionRejected("EXECUTION_MODEL_LOADING", 409, "provider_admission", retryable = true, inferenceAttempted = false)
        coEvery { client.create(any(), binding.request) } throws GatewayEngineeringRequestRejected(409, "Conversation revision changed")
        if (selectionChanged) coEvery { conversations.requireSavedSelection(binding) } throws IllegalStateException("The original provider connection changed")
        val repository = repository()
        val failure = runCatching { repository.reconnect("command") }.exceptionOrNull()
        assertTrue(failure is GatewayEngineeringRequestRejected)
        assertEquals(expectedUnknown, (failure as GatewayEngineeringRequestRejected).commandOutcomeUnknown)
        if (bindingRejected) {
            assertTrue(failure is GatewayExecutionRejected)
            assertEquals("EXECUTION_MODEL_LOADING", (failure as GatewayExecutionRejected).code)
            assertEquals(true, failure.retryable)
            assertTrue(failure.withCommandOutcomeUnknown(false).commandOutcomeUnknown)
        }
        assertEquals(expectedState, admissionState)
        repository.observe("command")
        coVerify(exactly = if (selectionChanged || bindingRejected) 0 else 1) { client.create(any(), any()) }
        coVerify(exactly = 1) { history.command(any(), "command") }
        coVerify(exactly = if (expectedUnknown || bindingRejected) 1 else 0) { history.observeCommand(any(), "command") }
    }

    @Test fun lostCreateResponseAndObserverCancellationRecoverAcceptedJobWithoutAnotherPostOrStop() = runTest {
        var binding = retainedBinding()
        val request = binding.request
        var state = "PENDING"
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { store.bindings(null) } answers { listOf(binding) }
        coEvery { store.admissionState("command") } answers { state }
        coEvery { store.markAdmission("command", any()) } answers { state = secondArg() }
        coEvery { store.save(any()) } answers {
            binding = firstArg()
            state = "ACCEPTED"
        }
        coEvery { client.principal(any(), requireReady = false) } returns "owner"
        coEvery { client.capabilities(any()) } returns capability
        coEvery { client.bindingCapabilities(any(), any(), any()) } returns requireNotNull(binding.executionAdmission)
        coEvery { history.command(any(), "command") } returns null
        coEvery { client.create(any(), request) } throws IOException("Response ended before headers")
        assertTrue(runCatching { repository().reconnect("command") }.exceptionOrNull() is IOException)
        assertEquals("OUTCOME_UNKNOWN", state)
        coEvery { history.observeCommand(any(), "command") } throws CancellationException("Observer detached")
        assertTrue(runCatching { repository().observe("command") }.exceptionOrNull() is CancellationException)
        assertEquals("OUTCOME_UNKNOWN", state)
        val job = GatewayEngineeringJob(
            id = "eng_" + "a".repeat(32), repository = request.repository, branch = request.branch, chatId = request.chatId,
            state = "QUEUED", revision = 0, createdAt = 1.0, deadline = 100.0, message = "Accepted",
            executionBindingId = request.executionBindingId, executionBinding = binding.executionAdmission?.binding,
            gatewayId = "gateway", principalId = "owner", conversationId = "original-conversation", commandId = "command"
        )
        coEvery { history.command(any(), "command") } returns GatewayCommandReceipt(gatewayId = "gateway", principalId = "owner", commandId = "command", conversationId = "original-conversation", jobKind = "engineering", jobId = job.id)
        coEvery { client.get(any(), job.id, any()) } returns job
        coEvery { conversations.recordEngineering(any()) } returns Unit
        val recovered = repository().reconnect("command")
        assertEquals(job.id, recovered.job?.id)
        assertEquals(request, recovered.request)
        assertEquals("ACCEPTED", state)
        coVerify(exactly = 1) { client.create(any(), request) }
        coVerify(exactly = 1) { store.deferCommandLookup("command") }
        coVerify(exactly = 0) { client.control(any(), any(), any()) }
        verify(exactly = 1) { workManager.enqueueUniquePeriodicWork(GatewayEngineeringRepository.OBSERVER_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, any()) }
    }
}
