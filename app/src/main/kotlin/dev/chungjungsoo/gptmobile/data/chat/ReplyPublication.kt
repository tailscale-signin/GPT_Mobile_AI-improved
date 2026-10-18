package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "reply_publications",
    foreignKeys = [
        ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageV2::class, parentColumns = ["message_id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatId", "profileUid"), Index("messageId"), Index("publicationSequence")]
)
data class ReplyPublication(
    @PrimaryKey val logicalReplyKey: String,
    val chatId: Int,
    val messageId: Int,
    val userTurnId: Int,
    val profileUid: String,
    val displayRole: String,
    val ownerFence: Long,
    val committedRevision: Long,
    val publishedAt: Long,
    val publicationSequence: Long,
    val acknowledgedRevision: Long,
    val deliveryKind: String,
    val contentDigest: String
)

object ReplyDisplayRole {
    const val DIRECT = "DIRECT_REPLY"
    const val MULTI = "MULTI_REPLY"
    const val CONTRIBUTOR = "COMBINED_CONTRIBUTOR"
    const val COMBINED = "COMBINED_FINAL"
    const val INTERNAL = "INTERNAL"
    fun visible(role: String) = role in setOf(DIRECT, MULTI, COMBINED)
}
