package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode

/** GitHub owns one profile. Old response records remain stored, but cannot become participants. */
internal object GitHubChatMembership {
    fun normalize(room: ChatRoomV2, context: GitHubConversationContext): ChatRoomV2 = room.copy(
        enabledPlatform = listOf(context.mainProfileUid),
        activePlatform = listOf(context.mainProfileUid),
        conversationMode = ConversationMode.STANDARD,
        combinedPrimaryUid = null
    )

    fun accepts(profileUids: List<String>, context: GitHubConversationContext): Boolean =
        profileUids.filter(String::isNotBlank).distinct() == listOf(context.mainProfileUid)
}
