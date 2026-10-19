package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversation_folders")
data class ConversationFolder(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'CUSTOM'") val colorMode: String = ConversationFolderStyle.CUSTOM
) {
    companion object {
        fun new(id: String, name: String, createdAt: Long, color: Long? = null) = ConversationFolder(
            id,
            ConversationFolderStyle.name(name),
            ConversationFolderStyle.color(color ?: ConversationFolderStyle.colors.first()),
            createdAt,
            if (color == null) ConversationFolderStyle.THEME else ConversationFolderStyle.CUSTOM
        )
    }
}

@Serializable
@Entity(
    tableName = "conversation_folder_members",
    foreignKeys = [
        ForeignKey(entity = ConversationFolder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("folderId")]
)
data class ConversationFolderMember(@PrimaryKey val chatId: Int, val folderId: String)

object ConversationFolderStyle {
    const val CUSTOM = "CUSTOM"
    const val THEME = "THEME"
    val colors = listOf(0xFF6650A4L, 0xFF006A6AL, 0xFF385E9DL, 0xFF875200L, 0xFF984061L, 0xFF54613BL)
    fun name(value: String): String = value.trim().also { require(it.isNotBlank() && it.length <= 40) { "Use 1–40 characters for a folder name." } }
    fun color(value: Long): Long = value.also { require(it in colors) { "Choose a folder colour." } }
}

@Serializable
@Entity(tableName = "conversation_folder_moves", foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)])
data class ConversationFolderMove(@PrimaryKey val chatId: Int, val version: String)
