package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.dto.ThemeBackupDto
import kotlinx.serialization.Serializable

@Serializable
data class ToolConnectionWithCredential(
    val connection: ToolConnection,
    val credentialPlaintext: String? = null
)

@Serializable
data class UiPreferencesBackupDto(
    val themeMode: Int = 0,
    val dynamicTheme: Boolean = false,
    val debugMode: Boolean = false,
    val localRuntimeBackend: String = "DEFAULT"
)

@Serializable
data class FavoriteItemBackupDto(
    val messageId: Int,
    val chatId: Int,
    val content: String,
    val platformType: String? = null,
    val isFavorite: Boolean = true,
    val createdAt: Long = 0L,
    val group: String? = null
)

@Serializable
data class FavoritesBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val favoriteIds: List<Int> = emptyList(),
    val favoriteGroups: List<String> = emptyList(),
    val messageGroups: Map<Int, String> = emptyMap(),
    val favoriteMessages: List<FavoriteItemBackupDto> = emptyList()
)

@Serializable
data class ConfigBackupPayload(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val theme: ThemeBackupDto? = null,
    val platforms: List<PlatformV2> = emptyList(),
    val toolConnections: List<ToolConnectionWithCredential> = emptyList(),
    val agentToolBindings: List<AgentToolBinding> = emptyList(),
    val favoriteGroups: List<String> = emptyList(),
    val messageGroups: Map<Int, String> = emptyMap(),
    val uiPreferences: UiPreferencesBackupDto? = null
)

@Serializable
data class DatabaseBackupPayload(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val chatRooms: List<ChatRoomV2> = emptyList(),
    val messages: List<MessageV2> = emptyList(),
    val chatPlatformModels: List<ChatPlatformModelV2> = emptyList(),
    val favoriteGroups: List<String> = emptyList(),
    val messageGroups: Map<Int, String> = emptyMap()
)

@Serializable
data class GranularBackupOptions(
    val includeFavorites: Boolean = true,
    val includePlatforms: Boolean = true,
    val includeTools: Boolean = true,
    val includeUiPreferences: Boolean = true,
    val includeConversations: Boolean = false,
    val passphrase: String? = null
)
