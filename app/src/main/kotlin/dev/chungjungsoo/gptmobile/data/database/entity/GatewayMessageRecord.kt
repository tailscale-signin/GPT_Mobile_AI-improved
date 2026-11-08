package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity

@Entity(tableName = "gateway_messages", primaryKeys = ["conversationKey", "messageId"])
data class GatewayMessageRecord(
    val conversationKey: String,
    val messageId: String,
    val turnId: String,
    val role: String,
    val content: String,
    val status: String,
    val revision: Long,
    val publicationId: String?,
    val createdAt: Double,
    val localMessageId: Int?
)
