package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayAvailabilityTargets

internal data class GatewayNavigationBinding(val inferenceProfileUid: String, val gatewayProfileUid: String)

internal data class GatewayNavigationFeatures(
    val savedSelectionChatIds: Set<Int>,
    val gitHubChatIds: Set<Int>,
    val researchChatIds: Set<Int>,
    val engineeringBindings: Map<Int, GatewayNavigationBinding>
)

internal fun gatewayNavigationFeatures(profiles: List<PlatformV2>, features: AppFeatureSettings): GatewayNavigationFeatures {
    val targets = gatewayAvailabilityTargets(profiles, features, forGitHub = true).filter { it.config != null }.associateBy { it.profileUid }
    return GatewayNavigationFeatures(
        features.conversationGateway.keys.toSet(),
        features.conversationGitHub.keys.toSet(),
        features.researchChatIds + features.conversationResearch.filterValues { it.researchToggle }.keys,
        features.conversationGitHub.mapNotNull { (chatId, context) ->
            targets[context.mainProfileUid]?.let { chatId to GatewayNavigationBinding(it.profileUid, it.gatewayProfileUid) }
        }.toMap()
    )
}

/** Failed admission can leave a valid gateway chat without a server job or mirror yet. */
internal fun gatewayNavigationChats(
    rooms: List<ChatRoomV2>,
    retainedChatIds: Set<Int>,
    savedSelectionChatIds: Set<Int>,
    verifiedProfileUids: Set<String>,
    engineeringBindings: Map<Int, GatewayNavigationBinding> = emptyMap()
): List<ChatRoomV2> = rooms.filter { room ->
    !room.isArchived &&
        (
            room.conversationMode == ConversationMode.GATEWAY ||
                room.id in retainedChatIds ||
                room.id in savedSelectionChatIds ||
                room.activePlatform.any { it in verifiedProfileUids } ||
                engineeringBindings[room.id]?.let { it.inferenceProfileUid in room.activePlatform && it.gatewayProfileUid in verifiedProfileUids } == true
            )
}.distinctBy { it.id }
