package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedSelection
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal data class GatewayChatAdoption(val room: ChatRoomV2, val blocker: String? = null)

internal class GatewayChatBoundaryUnavailable : IllegalStateException("The Gateway queue boundary could not be saved. Reopen this conversation when local storage is available.")

/** Creates an empty local room. No readiness probe or remote job is needed to start drafting. */
@Singleton
internal class GatewayChatCreator @Inject constructor(
    private val chats: ChatRepository,
    private val settings: SettingRepository,
    private val knownProfiles: GatewayKnownProfileStore,
    private val scopes: GatewayVerifiedScopeStore,
    private val database: ChatDatabaseV2
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val adoption = Mutex()

    suspend fun create(selection: GatewayQueuedSelection): ChatRoomV2 {
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == selection.profileUid }
            ?: error("The selected gateway profile is no longer available. Choose a profile again.")
        selection.validate(profile)?.let { error(it) }
        check(GatewayChatProfilePolicy.configured(profile)) { "Choose an enabled profile connected directly to a supported gateway." }
        check(knownProfiles.remembered(profile)) { "This profile has not yet confirmed saved gateway conversation support. Check its gateway connection, then try again." }
        val room = chats.createDraftChat(
            profileUids = listOf(selection.profileUid),
            conversationMode = ConversationMode.GATEWAY,
            title = "New Gateway Chat"
        )
        try {
            check(room.id > 0 && room.conversationMode == ConversationMode.GATEWAY && room.activePlatform == listOf(selection.profileUid)) {
                "The local gateway chat could not preserve its selected profile."
            }
            chats.saveChatPlatformModels(room.id, mapOf(selection.profileUid to selection.model))
            settings.mutateFeatureSettings { current ->
                check(current.conversationGateway[room.id] == null || current.conversationGateway[room.id] == selection) {
                    "The new gateway chat already has a different saved selection."
                }
                current.copy(conversationGateway = current.conversationGateway + (room.id to selection))
            }
            return room
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { chats.deleteChatsV2(listOf(room)) }
            }
            throw failure
        }
    }

    /** Opens retained work locally; serial queue ownership must not depend on a network read. */
    suspend fun adopt(chatId: Int, expectedProfileUid: String? = null): GatewayChatAdoption = adoption.withLock {
        val original = requireNotNull(database.gatewayJobDao().localChat(chatId)) { "The retained Gateway chat is unavailable." }
        // Existing Combined and engineering workflows keep their own continuation
        // and approval machinery. Their saved-job admission shares the global fence.
        // Merely opening history must not convert that workflow to ordinary inference.
        val records = database.gatewayJobDao().conversationsForChat(chatId)
        val repositoryConversation = records.any { record ->
            runCatching { json.decodeFromString<GatewayConversationSnapshot>(record.snapshot).repository != null }.getOrDefault(false)
        }
        val features = settings.getFeatureSettings()
        val nativeGitHubConversation = original.conversationMode != ConversationMode.GATEWAY && features.conversationGitHub[chatId] != null
        if (original.conversationMode == ConversationMode.COMBINED || database.gatewayJobDao().bindings(chatId).isNotEmpty() || repositoryConversation || nativeGitHubConversation) {
            return@withLock GatewayChatAdoption(original)
        }
        check(database.chatRoomDao().markGatewayChat(chatId) == 1) { "The retained Gateway chat could not be opened." }
        val room = original.copy(conversationMode = ConversationMode.GATEWAY, combinedPrimaryUid = null)
        try {
            check(!room.isTemporary) { "This saved Gateway transcript is read-only while its conversation is temporary." }
            val profiles = settings.fetchPlatformV2s()
            val retained = features.conversationGateway[chatId]
            if (retained != null) {
                val profile = checkNotNull(profiles.singleOrNull { it.uid == retained.profileUid }) { "The original Gateway profile is missing. Restore it before adding a message." }
                retained.validate(profile)?.let { error(it) }
                check(expectedProfileUid == null || expectedProfileUid == retained.profileUid) { "The saved Gateway participant differs from this server row. Its transcript remains read-only." }
                check(room.activePlatform == listOf(retained.profileUid)) { "Restore the original Gateway participant before adding a message." }
                check(database.gatewayJobDao().selectedModel(chatId, retained.profileUid) == retained.model) { "Restore the original Gateway model before adding a message." }
                return@withLock GatewayChatAdoption(room)
            }
            val selected = originalSelection(room, profiles, expectedProfileUid)
            settings.mutateFeatureSettings { current ->
                check(current.conversationGateway[chatId] == null || current.conversationGateway[chatId] == selected) { "The saved Gateway selection changed while opening its transcript." }
                current.copy(conversationGateway = current.conversationGateway + (chatId to selected))
            }
            GatewayChatAdoption(room)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            GatewayChatAdoption(room, failure.message ?: "Restore the original Gateway selection before adding a message. Its transcript remains readable.")
        } catch (_: Exception) {
            GatewayChatAdoption(room, "The original Gateway selection could not be verified. Its retained transcript is read-only.")
        }
    }

    /** A canonical retained local mirror can be opened even when its server is offline. */
    suspend fun localAttachment(connectionProfileUid: String, gatewayId: String, principalId: String, conversationId: String): GatewayConversationAttachment? {
        val key = gatewayIdentityKey(gatewayId, principalId, conversationId)
        val record = database.gatewayJobDao().conversation(key) ?: return null
        if (record.connectionProfileUid != connectionProfileUid || record.gatewayId != gatewayId || record.principalId != principalId || record.conversationId != conversationId) return null
        val chatId = record.localChatId ?: return null
        if (database.gatewayJobDao().localChat(chatId) == null) return null
        val context = settings.getFeatureSettings().conversationGitHub[chatId]
        return GatewayConversationAttachment(chatId, record.executionProfileUid, context, key, record.historyComplete && !record.syncHasMore)
    }

    /** A failed marker write must never open an ordinary composer as a Gateway chat. */
    suspend fun hasQueueBoundary(chatId: Int): Boolean = try {
        database.gatewayJobDao().localChat(chatId)?.conversationMode == ConversationMode.GATEWAY
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun originalSelection(room: ChatRoomV2, profiles: List<PlatformV2>, expectedProfileUid: String?): GatewayQueuedSelection {
        val dao = database.gatewayJobDao()
        check(dao.bindings(room.id).isEmpty()) { "This saved repository job keeps its original engineering route. Its transcript is read-only here; inspect or continue the original job from Jobs." }
        val records = dao.conversationsForChat(room.id)
        val recordsIdentity = records.map { record ->
            val snapshot = json.decodeFromString<GatewayConversationSnapshot>(record.snapshot)
            check(snapshot.repository == null) { "This saved repository job cannot become an ordinary Gateway chat. Its retained transcript is read-only." }
            check(snapshot.gatewayId == record.gatewayId && snapshot.principalId == record.principalId && snapshot.conversationId == record.conversationId) { "The saved Gateway conversation identity is incomplete. Reconnect before adding a message." }
            check(record.connectionProfileUid == record.executionProfileUid && snapshot.profileId == record.executionProfileUid) { "Restore the original direct Gateway profile before adding a message." }
            OriginalSelection(record.executionProfileUid, snapshot.model, record.endpoint, record.gatewayId, record.principalId)
        }
        val reservations = if (recordsIdentity.isNotEmpty()) {
            emptyList()
        } else {
            dao.submissionsForChat(room.id).map { submission ->
                val original = json.decodeFromString<GatewayOrdinaryReservation>(submission.payload)
                check(submission.connectionProfileUid == submission.executionProfileUid) { "The retained command uses a different Gateway route. Its transcript is read-only." }
                OriginalSelection(submission.executionProfileUid, original.model, submission.endpoint, original.gatewayId, original.principalId)
            }
        }
        val original = checkNotNull((recordsIdentity + reservations).distinct().singleOrNull()) { "The original Gateway route is missing or ambiguous. Reconnect to its saved conversation before adding a message." }
        check(expectedProfileUid == null || original.profileUid == expectedProfileUid) { "The saved Gateway participant differs from this server row." }
        check(room.activePlatform == listOf(original.profileUid)) { "Restore the original Gateway participant before adding a message." }
        val profile = checkNotNull(profiles.singleOrNull { it.uid == original.profileUid && it.enabled }) { "The original Gateway profile is missing or disabled. Restore it before adding a message." }
        check(profile.apiUrl.trimEnd('/') == original.endpoint && GatewayChatProfilePolicy.configured(profile.copy(model = original.model))) { "The original direct Gateway endpoint changed. Restore it before adding a message." }
        check(dao.selectedModel(room.id, profile.uid) == original.model) { "Restore the original Gateway model before adding a message." }
        val scope = scopes.verified(profile)
        check(scope?.gatewayId == original.gatewayId && scope.principalId == original.principalId) { "The original Gateway account has not been verified for this profile. Reconnect before adding a message." }
        check(knownProfiles.remembered(profile)) { "This Gateway has not confirmed saved conversation support for queued messages. Reconnect or update it; the retained transcript remains readable." }
        return GatewayQueuedSelection.capture(profile, original.model)
    }

    private data class OriginalSelection(val profileUid: String, val model: String, val endpoint: String, val gatewayId: String, val principalId: String)
}
