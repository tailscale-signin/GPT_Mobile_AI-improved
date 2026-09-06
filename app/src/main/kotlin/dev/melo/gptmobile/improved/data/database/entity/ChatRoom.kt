package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.melo.gptmobile.improved.data.model.ApiType

@Entity(tableName = "chat_rooms")
@TypeConverters(APITypeConverter::class)
data class ChatRoom(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "chat_id")
    val chatId: Int = 0,

    @ColumnInfo(name = "chat_title")
    val chatTitle: String,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "api_type")
    val apiType: ApiType,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false,

    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String? = null,

    @ColumnInfo(name = "temperature")
    val temperature: Float? = null,

    @ColumnInfo(name = "top_p")
    val topP: Float? = null,

    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int? = null,

    @ColumnInfo(name = "presence_penalty")
    val presencePenalty: Float? = null,

    @ColumnInfo(name = "frequency_penalty")
    val frequencyPenalty: Float? = null
)

class APITypeConverter {
    @TypeConverter
    fun fromAPIType(apiType: ApiType): String {
        return apiType.name
    }

    @TypeConverter
    fun toAPIType(name: String): ApiType {
        return try {
            ApiType.valueOf(name)
        } catch (_: IllegalArgumentException) {
            ApiType.OPENAI
        }
    }
}
