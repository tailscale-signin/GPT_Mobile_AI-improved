package dev.chungjungsoo.gptmobile.domain.model

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import kotlinx.serialization.Serializable

/**
 * Encapsulates the entire application backup payload including conversations,
 * starred favourites, advanced runtime configurations, and general settings.
 */
data class BackupData(
    val version: String = "1.0",
    val timestamp: Long = System.currentTimeMillis(),
    val conversations: List<ChatRoomV2> = emptyList(),
    val favourites: List<String> = emptyList(),
    val advancedSettings: Map<String, Any?> = emptyMap(),
    val generalSettings: Map<String, String> = emptyMap()
)

/**
 * Representation of an available backup operation (strictly 2 options: Create Backup, Restore Backup).
 */
data class BackupOption(
    val title: String,
    val description: String
)
