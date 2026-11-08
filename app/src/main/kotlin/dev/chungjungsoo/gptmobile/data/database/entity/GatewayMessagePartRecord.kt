package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.Index

/** Bounded physical parts are durable before a logical message is projected. */
@Entity(tableName = "gateway_message_parts", primaryKeys = ["conversationKey", "logicalMessageId", "revision", "partIndex"], indices = [Index(value = ["conversationKey", "createdAt", "logicalMessageId", "partIndex"])])
data class GatewayMessagePartRecord(
    val conversationKey: String,
    val logicalMessageId: String,
    val revision: Long,
    val partIndex: Int,
    val partCount: Int,
    val messageId: String,
    val turnId: String,
    val role: String,
    val kind: String,
    val content: String,
    val status: String,
    val publicationId: String?,
    val commandId: String?,
    val contentSha256: String?,
    val createdAt: Double,
    val contentVerified: Boolean = false
)
