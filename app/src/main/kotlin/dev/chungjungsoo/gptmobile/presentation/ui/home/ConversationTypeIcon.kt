package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.model.NewChatType

internal fun conversationType(room: ChatRoomV2, research: Boolean): NewChatType = when {
    research -> NewChatType.RESEARCH
    room.conversationMode == ConversationMode.COMBINED -> NewChatType.COMBINED
    room.enabledPlatform.size > 1 -> NewChatType.MULTI_CHAT
    else -> NewChatType.SINGLE
}

@DrawableRes
private fun conversationIconResource(type: NewChatType, unread: Boolean): Int = when (type) {
    NewChatType.RESEARCH -> if (unread) R.drawable.ic_conversation_research_unread else R.drawable.ic_conversation_research_seen
    NewChatType.COMBINED -> if (unread) R.drawable.ic_conversation_combined_unread else R.drawable.ic_conversation_combined_seen
    NewChatType.MULTI_CHAT -> if (unread) R.drawable.ic_conversation_multi_unread else R.drawable.ic_conversation_multi_seen
    NewChatType.SINGLE -> if (unread) R.drawable.ic_conversation_chat_unread else R.drawable.ic_conversation_chat_seen
}

/** Creation always uses the seen outline; only an unacknowledged published reply uses the fill. */
@Composable
internal fun conversationTypeIcon(type: NewChatType, unread: Boolean = false): ImageVector =
    ImageVector.vectorResource(conversationIconResource(type, unread))
