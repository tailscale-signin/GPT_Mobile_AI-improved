package dev.chungjungsoo.gptmobile.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Message data model
 */
@Serializable
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    val conversationId: Int,
    val content: String,
    val role: String, // "user" or "assistant"
    val timestamp: Long,
    val isEdited: Boolean = false,
    val isFavorite: Boolean = false,
    val attachments: List<String> = emptyList() // File paths or URLs
)