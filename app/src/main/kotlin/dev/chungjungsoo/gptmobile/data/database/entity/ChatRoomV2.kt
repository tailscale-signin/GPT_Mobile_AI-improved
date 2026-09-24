package dev.chungjungsoo.gptmobile.data.database.entity

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
@Entity(
    tableName = "chats_v2",
    indices = [
        Index(name = "index_chats_v2_archived_favorite_updated", value = ["is_archived", "is_favorite", "updated_at"]),
        Index(name = "index_chats_v2_archived_updated", value = ["is_archived", "updated_at"])
    ]
)
data class ChatRoomV2(
    /**
     Now, enabled platforms are stored as list of strings.
     The strings are UUID V4 strings from PlatformV2.uid
     */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "chat_id")
    val id: Int = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "enabled_platform")
    val enabledPlatform: List<String> = emptyList(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "draft_text")
    val draftText: String? = null,

    @ColumnInfo(name = "draft_updated_at")
    val draftUpdatedAt: Long? = null,

    @ColumnInfo(name = "is_title_customized", defaultValue = "0")
    val isTitleCustomized: Boolean = false,

    @ColumnInfo(name = "conversation_mode", defaultValue = "'STANDARD'")
    val conversationMode: String = ConversationMode.STANDARD
) : Parcelable

class StringListConverter {
    @TypeConverter
    fun fromString(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")

    @TypeConverter
    fun fromList(value: List<String>): String = if (value.isEmpty()) "" else value.joinToString(",")
}


object ConversationMode {
    const val STANDARD = "STANDARD"
    const val COMBINED = "COMBINED"

    fun normalize(value: String?): String =
        if (value.equals(COMBINED, ignoreCase = true)) COMBINED else STANDARD
}
