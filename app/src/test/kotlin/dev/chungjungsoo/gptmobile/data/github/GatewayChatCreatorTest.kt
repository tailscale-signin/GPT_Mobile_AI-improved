package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringBindingRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedSelection
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayChatCreatorTest {
    private val profile = PlatformV2(uid = "gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090", model = "chosen-model")
    private val selection = GatewayQueuedSelection.capture(profile, profile.model)
    private val room = ChatRoomV2(id = 42, title = "New Gateway Chat", enabledPlatform = listOf(profile.uid), activePlatform = listOf(profile.uid), conversationMode = ConversationMode.GATEWAY)
    private val chats = mockk<ChatRepository>()
    private val settings = mockk<SettingRepository>()
    private val known = mockk<GatewayKnownProfileStore>()
    private val scopes = mockk<GatewayVerifiedScopeStore>()
    private val database = mockk<ChatDatabaseV2>()
    private var features = AppFeatureSettings()
    private val creator = GatewayChatCreator(chats, settings, known, scopes, database)
    private val snapshot = GatewayConversationSnapshot(gatewayId = "gateway-id", principalId = "original-account", conversationId = "original-conversation", title = "Saved conversation", profileId = profile.uid, model = profile.model)
    private val original = GatewayConversationRecord(
        key = gatewayIdentityKey(snapshot.gatewayId, snapshot.principalId, snapshot.conversationId),
        gatewayId = snapshot.gatewayId,
        principalId = snapshot.principalId,
        conversationId = snapshot.conversationId,
        connectionProfileUid = profile.uid,
        endpoint = profile.apiUrl,
        executionProfileUid = profile.uid,
        localChatId = room.id,
        snapshot = Json.encodeToString(snapshot)
    )

    init {
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } coAnswers { features }
        coEvery { known.remembered(profile) } returns true
        coEvery { scopes.verified(any()) } returns null
        coEvery { chats.createDraftChat(listOf(profile.uid), ConversationMode.GATEWAY, "New Gateway Chat", null) } returns room
        coEvery { chats.saveChatPlatformModels(any(), any()) } returns Unit
        coEvery { settings.mutateFeatureSettings(any()) } coAnswers {
            features = firstArg<(AppFeatureSettings) -> AppFeatureSettings>().invoke(features)
            features
        }
        coEvery { chats.deleteChatsV2(any()) } returns Unit
        coEvery { database.gatewayJobDao().localChat(room.id) } returns room.copy(conversationMode = ConversationMode.STANDARD)
        coEvery { database.chatRoomDao().markGatewayChat(room.id) } returns 1
        coEvery { database.gatewayJobDao().selectedModel(room.id, profile.uid) } returns profile.model
        coEvery { database.gatewayJobDao().bindings(room.id) } returns emptyList()
        coEvery { database.gatewayJobDao().conversationsForChat(room.id) } returns listOf(original)
        coEvery { database.gatewayJobDao().submissionsForChat(room.id) } returns emptyList()
    }

    @Test fun offlineKnownProfileCreatesOneLocalRoomWithItsFrozenModelAndRoute() = runBlocking {
        assertEquals(room, creator.create(selection))
        assertEquals(selection, features.conversationGateway[room.id])
        coVerify(exactly = 1) { chats.createDraftChat(listOf(profile.uid), ConversationMode.GATEWAY, "New Gateway Chat", null) }
        coVerify(exactly = 1) { chats.saveChatPlatformModels(room.id, mapOf(profile.uid to "chosen-model")) }
        coVerify(exactly = 0) { chats.deleteChatsV2(any()) }
    }

    @Test fun unknownCandidateCannotCreateAGatewayRoom() = runBlocking {
        coEvery { known.remembered(profile) } returns false
        assertTrue(runCatching { creator.create(selection) }.isFailure)
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
        assertTrue(features.conversationGateway.isEmpty())
    }

    @Test fun editedDestinationOrDisabledProfileIsRejectedBeforeCreatingTheRoom() = runBlocking {
        for (changed in listOf(profile.copy(apiUrl = "http://192.168.1.5:8090"), profile.copy(enabled = false), profile.copy(compatibleType = ClientType.OPENAI))) {
            coEvery { settings.fetchPlatformV2s() } returns listOf(changed)
            assertTrue(runCatching { creator.create(selection) }.isFailure)
        }
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun failedBindingPersistenceRemovesOnlyTheJustCreatedDraft() = runBlocking {
        coEvery { settings.mutateFeatureSettings(any()) } throws IllegalStateException("storage failure")
        assertTrue(runCatching { creator.create(selection) }.isFailure)
        coVerify(exactly = 1) { chats.deleteChatsV2(listOf(room)) }
        assertFalse(features.conversationGateway.containsKey(room.id))
    }

    @Test fun cancellationDuringPersistenceCleansUpTheDraftAndPropagates() = runBlocking {
        coEvery { chats.saveChatPlatformModels(any(), any()) } throws CancellationException("left screen")
        assertTrue(runCatching { creator.create(selection) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 1) { chats.deleteChatsV2(listOf(room)) }
        assertTrue(features.conversationGateway.isEmpty())
    }

    @Test fun openingALegacyDirectConversationAdoptsItsOriginalLocalRoomAndFrozenModel() = runBlocking {
        coEvery { scopes.verified(profile) } returns GatewayVerifiedScope("a".repeat(64), snapshot.gatewayId, snapshot.principalId, 1)
        val adopted = creator.adopt(room.id, profile.uid)
        assertEquals(room.id, adopted.room.id)
        assertEquals(ConversationMode.GATEWAY, adopted.room.conversationMode)
        assertEquals(null, adopted.blocker)
        assertEquals(selection, features.conversationGateway[room.id])
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
        coVerify(exactly = 0) { chats.saveChatPlatformModels(any(), any()) }
    }

    @Test fun aMissingOriginalProfileKeepsTheMarkedTranscriptReadableWithoutCreatingAReplacement() = runBlocking {
        coEvery { settings.fetchPlatformV2s() } returns emptyList()
        val adopted = creator.adopt(room.id)
        assertEquals(ConversationMode.GATEWAY, adopted.room.conversationMode)
        assertTrue(adopted.blocker.orEmpty().contains("missing or disabled"))
        assertTrue(features.conversationGateway.isEmpty())
        coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun changedEndpointModelOrAccountCannotBecomeANewWritableBinding() = runBlocking {
        coEvery { scopes.verified(profile) } returns GatewayVerifiedScope("a".repeat(64), snapshot.gatewayId, "another-account", 1)
        assertTrue(creator.adopt(room.id).blocker.orEmpty().contains("account"))
        coEvery { database.gatewayJobDao().selectedModel(room.id, profile.uid) } returns "another-model"
        assertTrue(creator.adopt(room.id).blocker.orEmpty().contains("model"))
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(apiUrl = "http://192.168.1.8:8090"))
        assertTrue(creator.adopt(room.id).blocker.orEmpty().contains("endpoint"))
        assertTrue(features.conversationGateway.isEmpty())
    }

    @Test fun engineeringOwnershipNeverTurnsIntoAnOrdinaryGatewayInferenceChat() = runBlocking {
        coEvery { database.gatewayJobDao().bindings(room.id) } returns listOf(GatewayEngineeringBindingRecord("original-request", room.id, "job", "retained", 1))
        val adopted = creator.adopt(room.id)
        assertEquals(ConversationMode.STANDARD, adopted.room.conversationMode)
        assertEquals(null, adopted.blocker)
        assertTrue(features.conversationGateway.isEmpty())
        coVerify(exactly = 0) { database.chatRoomDao().markGatewayChat(any()) }
    }

    @Test fun combinedHistoryKeepsItsOriginalWriterAndContinuationMode() = runBlocking {
        val combined = room.copy(conversationMode = ConversationMode.COMBINED, combinedPrimaryUid = profile.uid)
        coEvery { database.gatewayJobDao().localChat(room.id) } returns combined
        val adopted = creator.adopt(room.id)
        assertEquals(combined, adopted.room)
        assertEquals(null, adopted.blocker)
        assertTrue(features.conversationGateway.isEmpty())
        coVerify(exactly = 0) { database.chatRoomDao().markGatewayChat(any()) }
    }

    @Test fun nativeGitHubConversationKeepsItsApprovalContinuationWithoutAGatewayBinding() = runBlocking {
        features = features.copy(conversationGitHub = mapOf(room.id to GitHubConversationContext("github", "owner", "repo", "feature", "a".repeat(40), profile.uid)))
        val adopted = creator.adopt(room.id)
        assertEquals(ConversationMode.STANDARD, adopted.room.conversationMode)
        assertEquals(null, adopted.blocker)
        assertTrue(features.conversationGateway.isEmpty())
        coVerify(exactly = 0) { database.chatRoomDao().markGatewayChat(any()) }
    }

    @Test fun profileOnlyLocalDraftCannotBecomeAReadOnlyGatewayAdoption() = runBlocking {
        val draft = room.copy(conversationMode = ConversationMode.STANDARD, draftText = "retained draft")
        coEvery { database.gatewayJobDao().localChat(room.id) } returns draft
        coEvery { database.gatewayJobDao().conversationsForChat(room.id) } returns emptyList()
        val opened = creator.adopt(room.id)
        assertEquals(draft, opened.room)
        assertEquals(null, opened.blocker)
        coVerify(exactly = 0) { database.chatRoomDao().markGatewayChat(any()) }
        coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
    }

    @Test fun researchAndMultiChatsKeepTheirWorkflowsWhenAGatewayParticipantHasRetainedHistory() = runBlocking {
        val standard = room.copy(conversationMode = ConversationMode.STANDARD)
        features = features.copy(researchChatIds = setOf(room.id))
        coEvery { database.gatewayJobDao().localChat(room.id) } returns standard
        assertEquals(standard, creator.adopt(room.id).room)
        assertEquals(setOf(room.id), features.researchChatIds)

        features = AppFeatureSettings()
        val multi = standard.copy(enabledPlatform = listOf(profile.uid, "other"), activePlatform = listOf(profile.uid, "other"))
        coEvery { database.gatewayJobDao().localChat(room.id) } returns multi
        assertEquals(multi, creator.adopt(room.id).room)
        coVerify(exactly = 0) { database.chatRoomDao().markGatewayChat(any()) }
        coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
    }

    @Test fun changedParticipantsOnAnExplicitGatewayChatStillRequireItsFrozenSelection() = runBlocking {
        features = features.copy(conversationGateway = mapOf(room.id to selection))
        val changed = room.copy(activePlatform = listOf(profile.uid, "other"))
        coEvery { database.gatewayJobDao().localChat(room.id) } returns changed
        val opened = creator.adopt(room.id)
        assertEquals(ConversationMode.GATEWAY, opened.room.conversationMode)
        assertTrue(opened.blocker.orEmpty().contains("original Gateway participant"))
        assertEquals(selection, features.conversationGateway[room.id])
    }

    @Test fun existingFrozenSelectionIsPreservedWhenTheConfiguredDefaultModelChanges() = runBlocking {
        features = features.copy(conversationGateway = mapOf(room.id to selection))
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(model = "new-default-model"))
        val adopted = creator.adopt(room.id)
        assertEquals(null, adopted.blocker)
        assertEquals(selection, features.conversationGateway[room.id])
        coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
    }

    @Test fun localServerAttachmentUsesCanonicalRetainedIdentityWithoutARequest() = runBlocking {
        coEvery { database.gatewayJobDao().conversation(original.key) } returns original
        val attachment = creator.localAttachment(profile.uid, snapshot.gatewayId, snapshot.principalId, snapshot.conversationId)
        assertEquals(room.id, attachment?.chatId)
        assertEquals(original.key, attachment?.conversationKey)
        assertEquals(null, creator.localAttachment("another-profile", snapshot.gatewayId, snapshot.principalId, snapshot.conversationId))
    }
}
