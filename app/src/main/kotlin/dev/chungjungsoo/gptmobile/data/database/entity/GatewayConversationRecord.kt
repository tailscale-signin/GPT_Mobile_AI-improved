package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_conversations", indices = [Index(value = ["localChatId"])])
data class GatewayConversationRecord(
    @PrimaryKey val key: String,
    val gatewayId: String,
    val principalId: String,
    val conversationId: String,
    val connectionProfileUid: String,
    val endpoint: String,
    val executionProfileUid: String,
    val localChatId: Int?,
    val snapshot: String,
    val revision: Long = -1,
    val messageCursor: String? = null,
    val syncedAtMillis: Long = 0,
    val syncHasMore: Boolean = false,
    val historyComplete: Boolean = true,
    val historyLimitation: String? = null
)
