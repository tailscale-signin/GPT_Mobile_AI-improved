package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "chat_rooms_v2")
@TypeConverters(StringListConverter::class)
data class ChatRoomV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "chat_id")
    val chatId: Int = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "active_platform_uid")
    val activePlatformUid: String,

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
    val frequencyPenalty: Float? = null,

    @ColumnInfo(name = "enabled_mcp_servers", defaultValue = "[]")
    val enabledMcpServers: List<String> = emptyList(),

    @ColumnInfo(name = "mcp_tool_execution_mode", defaultValue = "ALWAYS_APPROVE")
    val mcpToolExecutionMode: String = "ALWAYS_APPROVE"
)

class StringListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromString(value: String): List<String> {
        return try {
            json.decodeFromString<List<String>>(value)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        return json.encodeToString(list)
    }
}
