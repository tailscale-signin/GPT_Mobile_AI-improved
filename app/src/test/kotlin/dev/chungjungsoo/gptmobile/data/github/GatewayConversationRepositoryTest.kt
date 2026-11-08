package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConversationRepositoryTest {
    private class Fixture {
        val profile = PlatformV2(uid = "profile", name = "Selected model", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080/v1", model = "exact-model")
        val gateway = profile.copy(uid = "gateway-profile", name = "Desktop gateway", apiUrl = "http://127.0.0.1:8081/v1")
        val context = GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), profile.uid)
        val settings = mockk<SettingRepository>()
        val store = mockk<GatewayWorkStore>(relaxed = true)
        val dao = mockk<GatewayWorkDao>(relaxed = true)
        val client = mockk<GatewayConversationClient>()
        val engineering = mockk<GatewayEngineeringClient>()
        val identity = GatewayIdentity(1, "installation", "process", "principal", "account", 1000)
        val selection = executionSelection(profile, null, "principal/chat/7/repository/owner/repo")
        val admission = GatewayExecutionAdmission(
            buildJsonObject {
                put("binding_id", "bind_" + "b".repeat(32))
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
            },
            buildJsonObject {
                put("requested_model_id", "exact-model")
                put("reported_model_id", "exact-model")
            }
        )
        val job = GatewayEngineeringJob("eng_" + "c".repeat(32), "owner/repo", "feature", 7, "COMPLETED", 2, 1.0, 100.0, "Complete", executionBindingId = admission.bindingId, executionBinding = admission.binding, gatewayId = "installation", principalId = "principal", conversationId = "conversation", acceptedAtMs = 1000, serverTimeMs = 2000, schedulingState = "completed", conversationRevision = 2, sourceBranch = "feature", sourceSha = "a".repeat(40))
        var binding: GatewayEngineeringBinding? = null
        var command: GatewayCommandRecord? = null
        var features = AppFeatureSettings(githubProfileOverrides = mapOf("profile" to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway-profile")))
        val states = mutableListOf<String>()
        val repository = GatewayConversationRepository(settings, store, client, engineering, mockk<ChatDatabaseV2>())

        init {
            every { store.dao } returns dao
            coEvery { settings.getFeatureSettings() } answers { features }
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile, gateway)
            coEvery { store.binding(any()) } answers { binding?.takeIf { it.request.requestId == firstArg<String>() } }
            coEvery { store.bindings(any()) } answers { listOfNotNull(binding) }
            coEvery { dao.command(any()) } answers { command?.takeIf { it.id == firstArg<String>() } }
            coEvery { dao.conversations(any()) } returns emptyList()
            coEvery { store.prepare(any(), any(), any()) } coAnswers {
                binding = firstArg()
                val saved = requireNotNull(binding)
                command = GatewayCommandRecord(saved.request.requestId, "scope", saved.conversationId, saved.chatId, saved.userMessageId, saved.assistantMessageId, saved.localRunId, secondArg(), "digest", thirdArg(), "PREPARED", null, 1000, 1000)
                true
            }
            coEvery { store.commandState(any(), any(), any()) } coAnswers {
                states += secondArg<String>()
                command = command?.copy(state = secondArg(), error = thirdArg())
            }
            coEvery { store.accept(any()) } coAnswers {
                binding = firstArg()
                command = command?.copy(state = "ACCEPTED", jobId = binding?.job?.id, conversationKey = "conversation")
            }
            coEvery { store.saveBinding(any()) } coAnswers { binding = firstArg() }
            coEvery { client.identity(any()) } returns identity
            coEvery { client.command(any(), any()) } returns null
            coEvery { engineering.principal(any(), any()) } returns "principal"
            coEvery { engineering.prepareBinding(any(), any()) } returns admission
            coEvery { engineering.bindingCapabilities(any(), any(), any()) } returns admission
            coEvery { engineering.get(any(), any(), any()) } returns job.copy(nextRevision = 2)
        }

        suspend fun submit(selected: PlatformV2 = profile) = repository.submitNormalTurn(7, context, selected, "request", "Read the repository", "read", 11, 12, localRunId = "run")
        fun receipt() = GatewayCommandReceipt("request", "accepted", "installation", "principal", "conversation", job.id, 2, 1000, 2000, job)
    }

    @Test fun lostAdmissionResponseIsReconciledWithoutSecondCreate() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } throws IOException("lost response")
        assertEquals(null, f.submit().job)
        assertTrue(f.repository.ownsRequest("request"))
        assertEquals("UNKNOWN", f.command?.state)
        coEvery { f.client.command(any(), "request") } returns f.receipt()
        val recovered = f.repository.observeRequest("request")
        assertEquals(f.job.id, recovered.job?.id)
        assertEquals("ACCEPTED", f.command?.state)
        coVerify(exactly = 1) { f.engineering.create(any(), any()) }
        coVerify(exactly = 0) { f.client.followUp(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun unknownCommandReconnectNeverCreatesWork() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } throws IOException("lost response")
        f.submit()
        f.repository.reconnectConversation(7)
        assertEquals("UNKNOWN", f.command?.state)
        coVerify(exactly = 1) { f.engineering.create(any(), any()) }
        coVerify(exactly = 2) { f.client.command(any(), "request") }
    }

    @Test fun secondSendCannotCreateAnotherConversationWhileOriginalAdmissionIsUnknown() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } throws IOException("lost response")
        f.submit()
        val originalPayload = f.command?.payload
        val failure = runCatching { f.repository.submitNormalTurn(7, f.context, f.profile, "second-command", "A second draft", "read", 13, 14, localRunId = "second-run") }.exceptionOrNull()
        assertTrue(requireNotNull(failure?.message).contains("previous saved submission is still unconfirmed"))
        assertEquals("request", f.command?.id)
        assertEquals(originalPayload, f.command?.payload)
        assertFalse(f.repository.ownsRequest("second-command"))
        coVerify(exactly = 1) { f.store.prepare(any(), any(), any()) }
        coVerify(exactly = 1) { f.engineering.create(any(), any()) }
        coVerify(exactly = 2) { f.client.command(any(), "request") }
        coVerify(exactly = 0) { f.client.followUp(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun followUpPreservesOriginalSourceCommitAfterWorkspaceRefresh() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } returns f.job
        f.submit()
        val scope = GatewayScopeRecord("scope", f.gateway.uid, selectedEndpoint(f.gateway.apiUrl), "installation", "principal", "account", "ONLINE", 1000)
        val saved = GatewayConversationRecord("local", "scope", "conversation", 7, f.profile.uid, f.profile.name, f.profile.model, "owner/repo", "feature", "Original", 3, f.admission.binding.toString(), Json.encodeToString(f.context), true, null, 2000, "a".repeat(40))
        coEvery { f.dao.conversations(7) } returns listOf(saved)
        coEvery { f.dao.scope("scope") } returns scope
        coEvery { f.store.verifiedScope(any(), any(), any()) } returns scope
        coEvery { f.client.conversation(any(), "conversation") } returns GatewayConversationSnapshot("installation", "principal", "conversation", 3, "Original", f.admission.binding, f.profile.uid, f.profile.name, f.profile.model, "owner/repo", "feature", serverTimeMs = 2000, sourceBranch = "feature", sourceSha = "a".repeat(40))
        coEvery { f.store.acceptSnapshot(any(), any()) } returns saved
        val next = f.job.copy(id = "eng_" + "d".repeat(32), conversationRevision = 4)
        coEvery { f.client.followUp(any(), "conversation", "follow-up", 3, "Continue", "read") } returns GatewayCommandReceipt("follow-up", "accepted", "installation", "principal", "conversation", next.id, 4, 2000, 2000, next)
        val accepted = f.repository.submitNormalTurn(7, f.context.copy(commitSha = "b".repeat(40)), f.profile, "follow-up", "Continue", "read", 13, 14, localRunId = "follow-up-run")
        assertEquals("a".repeat(40), accepted.request.sourceSha)
        assertEquals(f.context, accepted.originalContext)
        assertEquals(next.id, accepted.job?.id)
        coVerify(exactly = 1) { f.engineering.create(any(), any()) }
        coVerify(exactly = 1) { f.client.followUp(any(), "conversation", "follow-up", 3, "Continue", "read") }
    }

    @Test fun remotelyOpenedFollowUpRetainsCustomWorkingBranchAndOriginalBase() = runTest {
        val f = Fixture()
        val scope = GatewayScopeRecord("scope", f.gateway.uid, selectedEndpoint(f.gateway.apiUrl), "installation", "principal", "account", "ONLINE", 1000)
        val saved = GatewayConversationRecord("local", "scope", "conversation", 7, f.profile.uid, f.profile.name, f.profile.model, "owner/repo", "feature", "Original", 3, f.admission.binding.toString(), Json.encodeToString(f.context), true, null, 2000, "a".repeat(40))
        val previous = GatewayCatalogJob(f.job.id, "engineering", "conversation", 2, "COMPLETED", "completed", profileId = f.profile.uid, model = f.profile.model, repository = "owner/repo", branch = "feature", updatedAtMs = 2000, serverTimeMs = 2000, sourceBranch = "feature", sourceSha = "a".repeat(40), workingBranch = "work/custom", base = "release")
        coEvery { f.dao.conversations(7) } returns listOf(saved)
        coEvery { f.dao.scope("scope") } returns scope
        coEvery { f.store.verifiedScope(any(), any(), any()) } returns scope
        coEvery { f.client.conversation(any(), "conversation") } returns GatewayConversationSnapshot("installation", "principal", "conversation", 3, "Original", f.admission.binding, f.profile.uid, f.profile.name, f.profile.model, "owner/repo", "feature", serverTimeMs = 2000, sourceBranch = "feature", sourceSha = "a".repeat(40), workingBranch = "work/custom", latestJob = previous)
        coEvery { f.store.acceptSnapshot(any(), any()) } returns saved
        val next = f.job.copy(id = "eng_" + "d".repeat(32), branch = "work/custom", workingBranch = "work/custom", base = "release", conversationRevision = 4)
        coEvery { f.client.followUp(any(), "conversation", "follow-up", 3, "Continue", "read") } returns GatewayCommandReceipt("follow-up", "accepted", "installation", "principal", "conversation", next.id, 4, 2000, 2000, next)
        val accepted = f.repository.submitNormalTurn(7, f.context.copy(commitSha = "b".repeat(40)), f.profile, "follow-up", "Continue", "read", 13, 14, localRunId = "follow-up-run")
        assertEquals("work/custom", accepted.request.branch)
        assertEquals("release", accepted.request.base)
        assertEquals("feature", accepted.request.sourceBranch)
        assertEquals("a".repeat(40), accepted.request.sourceSha)
        assertEquals(f.context, accepted.originalContext)
        assertEquals(next.id, accepted.job?.id)
        coVerify(exactly = 0) { f.engineering.create(any(), any()) }
        coVerify(exactly = 1) { f.client.followUp(any(), "conversation", "follow-up", 3, "Continue", "read") }
    }

    @Test fun explicitRetryUsesExactSavedIdentityAndPayloadAfterReadOnlyReconciliation() = runTest {
        val f = Fixture()
        val requests = mutableListOf<GatewayEngineeringRequest>()
        coEvery { f.engineering.create(any(), any()) } coAnswers {
            requests += secondArg<GatewayEngineeringRequest>()
            if (requests.size == 1) throw IOException("request not delivered") else f.job
        }
        f.submit()
        val durable = f.command?.payload
        f.repository.reconnectConversation(7)
        assertEquals(1, requests.size)
        val accepted = f.repository.retrySavedSubmission("request")
        assertEquals(f.job.id, accepted.job?.id)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals("request", requests[1].requestId)
        assertEquals(durable, f.command?.payload)
        coVerify(exactly = 3) { f.client.command(any(), "request") }
    }

    @Test fun definitiveRevisionConflictIsReportedAsRejectedWithOriginalCommandRetained() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } throws GatewayEngineeringHttpFailure(409, "Revision changed")
        f.submit()
        assertEquals("REJECTED", f.command?.state)
        assertTrue(f.repository.ownsRequest("request"))
        coVerify(exactly = 1) { f.engineering.create(any(), any()) }
    }

    @Test fun forbiddenReceiptReadCannotClaimThatAnUncertainSubmissionWasRejected() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } throws IOException("lost admission response")
        f.submit()
        coEvery { f.client.command(any(), "request") } throws GatewayContinuityFailure(403)
        f.repository.reconnectConversation(7)
        assertEquals("UNKNOWN", f.command?.state)
        assertTrue(f.repository.ownsRequest("request"))
        coVerify(exactly = 1) { f.engineering.create(any(), any()) }
    }

    @Test fun explicitChatModelOverlayIsAdmittedWhileRawProfileRemainsUnchanged() = runTest {
        val f = Fixture()
        val overlay = f.profile.copy(model = "chat-selected-model", reasoning = !f.profile.reasoning)
        val selected = executionSelection(overlay, null, f.selection.privacyScope)
        val admitted = GatewayExecutionAdmission(
            buildJsonObject {
                f.admission.binding.forEach { (key, value) -> put(key, value) }
                put("profile_revision", selected.profileRevision)
                put("requested_model_id", selected.requestedModelId)
                put("generation_parameters", selected.generationParameters)
            },
            buildJsonObject {
                put("requested_model_id", selected.requestedModelId)
                put("reported_model_id", selected.requestedModelId)
            }
        )
        coEvery { f.engineering.prepareBinding(any(), selected) } returns admitted
        coEvery { f.engineering.bindingCapabilities(any(), selected, any()) } returns admitted
        coEvery { f.engineering.create(any(), any()) } returns f.job.copy(executionBinding = admitted.binding)
        val accepted = f.submit(overlay)
        assertEquals("chat-selected-model", accepted.request.model)
        assertEquals(selected, accepted.executionSelection)
    }

    @Test fun cancelledSubmissionRetainsDurableOwnership() = runTest {
        val f = Fixture()
        coEvery { f.engineering.create(any(), any()) } throws CancellationException("screen stopped")
        assertTrue(runCatching { f.submit() }.exceptionOrNull() is CancellationException)
        assertTrue(f.repository.ownsRequest("request"))
        assertEquals("UNKNOWN", f.command?.state)
        coVerify(exactly = 1) { f.store.prepare(any(), any(), any()) }
    }

    @Test fun disabledRemoteToolsBlockBeforeMetadataOrOutbox() = runTest {
        val f = Fixture()
        assertTrue(runCatching { f.submit(f.profile.copy(disableRemoteTools = true)) }.isFailure)
        coVerify(exactly = 0) { f.client.identity(any()) }
        coVerify(exactly = 0) { f.store.prepare(any(), any(), any()) }
        assertFalse(f.repository.ownsRequest("request"))
    }

    @Test fun storedConsentChangeBlocksStaleCapturedProfileBeforeMetadata() = runTest {
        val f = Fixture()
        coEvery { f.settings.fetchPlatformV2s() } returns listOf(f.profile.copy(disableAllTools = true), f.gateway)
        assertTrue(runCatching { f.submit() }.isFailure)
        coVerify(exactly = 0) { f.client.identity(any()) }
        coVerify(exactly = 0) { f.engineering.create(any(), any()) }
    }

    @Test fun chatConsentRevokedDuringLastPreflightPreventsPostButRetainsOwnership() = runTest {
        val f = Fixture()
        var allowed = true
        coEvery { f.engineering.bindingCapabilities(any(), any(), any()) } coAnswers {
            allowed = false
            f.admission
        }
        val retained = f.repository.submitNormalTurn(7, f.context, f.profile, "request", "Read the repository", "read", 11, 12, localRunId = "run", admissionAllowed = { allowed })
        assertEquals(null, retained.job)
        assertTrue(f.repository.ownsRequest("request"))
        assertEquals("UNKNOWN", f.command?.state)
        assertTrue(requireNotNull(f.command?.error).contains("Tool consent changed"))
        coVerify(exactly = 1) { f.store.prepare(any(), any(), any()) }
        coVerify(exactly = 0) { f.engineering.create(any(), any()) }
        coVerify(exactly = 0) { f.client.followUp(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun modelEditDuringPreflightCannotChangeOriginalAdmission() = runTest {
        val f = Fixture()
        coEvery { f.engineering.prepareBinding(any(), any()) } coAnswers {
            coEvery { f.settings.fetchPlatformV2s() } returns listOf(f.profile.copy(model = "another-model"), f.gateway)
            f.admission
        }
        assertTrue(runCatching { f.submit() }.isFailure)
        coVerify(exactly = 0) { f.store.prepare(any(), any(), any()) }
        coVerify(exactly = 0) { f.engineering.create(any(), any()) }
    }
}
