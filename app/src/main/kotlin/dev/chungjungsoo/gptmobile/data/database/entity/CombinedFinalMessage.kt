package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One logical final per accepted user turn; the writer's contribution has its own message. */
@Entity(
    tableName = "combined_final_messages",
    primaryKeys = ["chatId", "userMessageId"],
    foreignKeys = [
        ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageV2::class, parentColumns = ["message_id"], childColumns = ["userMessageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageV2::class, parentColumns = ["message_id"], childColumns = ["finalMessageId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("userMessageId"), Index(value = ["finalMessageId"], unique = true)]
)
data class CombinedFinalMessage(val chatId: Int, val userMessageId: Int, val finalMessageId: Int)
