package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable local admission identity, written before dispatch and reused after lost replies. */
@Entity(tableName = "gateway_submissions", indices = [Index(value = ["chatId"]), Index(value = ["conversationKey"])])
data class GatewaySubmissionRecord(
    @PrimaryKey val commandId: String,
    val chatId: Int,
    val userMessageId: Int?,
    val assistantMessageId: Int?,
    val runId: String?,
    val connectionProfileUid: String,
    val endpoint: String,
    val executionProfileUid: String,
    val fingerprint: String,
    val payload: String,
    val state: String = "PENDING",
    val conversationKey: String? = null,
    val receipt: String? = null,
    val createdAtMillis: Long
)
