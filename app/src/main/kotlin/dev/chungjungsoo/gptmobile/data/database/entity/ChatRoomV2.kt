package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "chats_v2")
data class ChatRoomV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "chat_id")
    val chatId: Int = 0,
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Date = Date(),
    @ColumnInfo(name = "model_name")
    val modelName: String? = null,
    val systemPrompt: String? = null,
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false
)
