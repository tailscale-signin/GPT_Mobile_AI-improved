package dev.chungjungsoo.gptmobile.data.database.entity

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "chats_v2")
@Parcelize
data class ChatRoomV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "chat_id")
    val id: Int = 0,
    val title: String,
    @ColumnInfo(name = "enabled_platform")
    @TypeConverters(StringListConverter::class)
    val enabledPlatform: List<String>,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false
) : Parcelable

class StringListConverter {
    @TypeConverter
    fun fromString(value: String): List<String> = Json.decodeFromString(value)

    @TypeConverter
    fun fromList(list: List<String>): String = Json.encodeToString(list)
}
