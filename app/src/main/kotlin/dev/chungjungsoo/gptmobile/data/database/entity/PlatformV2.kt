package dev.chungjungsoo.gptmobile.data.database.entity

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.GeminiSafetySettings
import java.util.UUID
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
@Entity(tableName = "platform_v2")
data class PlatformV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "platform_id")
    val id: Int = 0,

    @ColumnInfo(name = "uid")
    val uid: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    @ColumnInfo(name = "api_url")
    val apiUrl: String = "",

    @ColumnInfo(name = "token")
    val token: String? = null,

    @ColumnInfo(name = "model")
    val model: String = "",

    @ColumnInfo(name = "temperature")
    val temperature: Float? = null,

    @ColumnInfo(name = "top_p")
    val topP: Float? = null,

    @ColumnInfo(name = "top_k")
    val topK: Int? = null,

    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int? = null,

    @ColumnInfo(name = "timeout")
    val timeout: Int = 60,

    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String? = null,

    @ColumnInfo(name = "compatible_type")
    val compatibleType: ClientType = ClientType.OPENAI,

    @ColumnInfo(name = "max_tool_calls", defaultValue = "2147483647")
    val maxToolCalls: Int = Int.MAX_VALUE,

    @ColumnInfo(name = "harassment_safety_threshold", defaultValue = "'BLOCK_NONE'")
    val harassmentSafetyThreshold: String = GeminiSafetySettings.BLOCK_NONE,

    @ColumnInfo(name = "hate_speech_safety_threshold", defaultValue = "'BLOCK_NONE'")
    val hateSpeechSafetyThreshold: String = GeminiSafetySettings.BLOCK_NONE,

    @ColumnInfo(name = "sexually_explicit_safety_threshold", defaultValue = "'BLOCK_NONE'")
    val sexuallyExplicitSafetyThreshold: String = GeminiSafetySettings.BLOCK_NONE,

    @ColumnInfo(name = "dangerous_content_safety_threshold", defaultValue = "'BLOCK_NONE'")
    val dangerousContentSafetyThreshold: String = GeminiSafetySettings.BLOCK_NONE,

    @ColumnInfo(name = "open_router_routing")
    val openRouterRouting: String? = null,

    @ColumnInfo(name = "disable_all_tools", defaultValue = "0")
    val disableAllTools: Boolean = false,

    @ColumnInfo(name = "disable_remote_tools", defaultValue = "0")
    val disableRemoteTools: Boolean = false,

    @ColumnInfo(name = "disable_local_tools", defaultValue = "0")
    val disableLocalTools: Boolean = false,

    @ColumnInfo(name = "ollama_options")
    val ollamaOptions: String? = null,

    @ColumnInfo(name = "labels")
    val labels: String? = null,

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false
) : Parcelable
