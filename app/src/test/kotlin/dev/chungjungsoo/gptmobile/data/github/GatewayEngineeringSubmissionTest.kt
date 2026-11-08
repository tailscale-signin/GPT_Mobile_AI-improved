package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayEngineeringSubmissionTest {
    private val settings = mockk<SettingRepository>()
    private val client = mockk<GatewayEngineeringClient>()
    private val store = mockk<GatewayEngineeringStore>()
    private val conversations = mockk<GatewayConversationRepository>()
    private val history = mockk<GatewayJobClient>()
    private val selected = PlatformV2(uid = "execution-profile", name = "Selected inference", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.8:8080/v1", model = "profile-default")
    private val originalGateway = PlatformV2(uid = "original-gateway", name = "Original gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.9:8090/v1", model = "unused")
    private val capability = GatewayEngineeringCapabilities("engineering-gateway", 1, enabled = true, principalId = "owner", gatewayId = "gateway", taskScopeProtocol = 1, taskScopes = listOf("read", "plan", "implement"), conversationSync = true, durableDetach = true)
    private fun repository() = GatewayEngineeringRepository(mockk<Context>(), settings, client, mockk(), store, conversations, history)

    @Before fun admissionDefaults() {
        coEvery { store.bindings(8) } returns emptyList()
        coEvery { store.hasMigratedAdmission(any(), any()) } returns false
        coEvery { history.identity(any()) } returns GatewayScopeIdentity("gateway", "owner")
        coEvery { conversations.requireSavedSelection(any()) } returns Unit
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf(selected.uid to GitHubChatProfileOverride(githubTools = true)))
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
        for (request in listOf("Fix it but do not commit", "Implement without commits", "Change it; never push", "Update this, don't commit or push")) {
            assertTrue(runCatching { requireGatewayCommitPermission(request, "implement") }.isFailure)
            requireGatewayCommitPermission(request, "read")
            requireGatewayCommitPermission(request, "plan")
        }
        requireGatewayCommitPermission("Implement and verify the feature", "implement")
    }

    @Test fun rejectedFirstAdmissionIsNotObservedAsUnconfirmedRunningWork() = runTest {
        rejectedAdmission("PENDING", "REJECTED:409", expectedUnknown = false)
    }

    @Test fun rejectedRetryCannotEraseAnEarlierUncertainDispatch() = runTest {
        rejectedAdmission("OUTCOME_UNKNOWN", "OUTCOME_UNKNOWN", expectedUnknown = true)
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
        coEvery { history.command(any(), "original-command") } returns null
        val repository = repository()
        assertTrue(repository.hasUnconfirmedAdmission(8, selected.uid))
        assertTrue(runCatching { repository.requireNoUnconfirmedAdmission(8, selected.uid, "new-command") }.isFailure)
        coVerify(exactly = 1) { history.command(any(), "original-command") }
        coVerify(exactly = 0) { client.create(any(), any()) }
    }

    private suspend fun rejectedAdmission(initial: String, expectedState: String, expectedUnknown: Boolean, selectionChanged: Boolean = false) {
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
        }
        val admission = GatewayExecutionAdmission(
            bindingJson,
            buildJsonObject {
                put("requested_model_id", selection.requestedModelId)
                put("reported_model_id", selection.requestedModelId)
            }
        )
        val binding = GatewayEngineeringBinding(originalGateway.uid, originalGateway.apiUrl, GatewayEngineeringRequest("owner/repo", "work", "main", "Inspect", selected.model, chatId = 8, requestId = "command", profileId = selected.uid, executionBindingId = admission.bindingId, taskScope = "read"), principalId = "owner", executionSelection = selection, executionAdmission = admission, gatewayId = "gateway")
        var admissionState = initial
        coEvery { settings.fetchPlatformV2s() } returns listOf(selected, originalGateway)
        coEvery { store.bindings(null) } returns listOf(binding)
        coEvery { store.admissionState("command") } answers { admissionState }
        coEvery { store.markAdmission("command", any()) } answers { admissionState = secondArg() }
        coEvery { client.principal(any(), requireReady = false) } returns "owner"
        coEvery { client.capabilities(any()) } returns capability
        coEvery { history.command(any(), "command") } returns null
        coEvery { client.bindingCapabilities(any(), selection, admission.bindingId) } returns admission
        coEvery { client.create(any(), binding.request) } throws GatewayEngineeringRequestRejected(409, "Conversation revision changed")
        if (selectionChanged) coEvery { conversations.requireSavedSelection(binding) } throws IllegalStateException("The original provider connection changed")
        val repository = repository()
        val failure = runCatching { repository.reconnect("command") }.exceptionOrNull()
        assertTrue(failure is GatewayEngineeringRequestRejected)
        assertEquals(expectedUnknown, (failure as GatewayEngineeringRequestRejected).commandOutcomeUnknown)
        assertEquals(expectedState, admissionState)
        repository.observe("command")
        coVerify(exactly = if (selectionChanged) 0 else 1) { client.create(any(), any()) }
        coVerify(exactly = if (expectedUnknown) 2 else 1) { history.command(any(), "command") }
    }
}
