package com.example.gptmobileai.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chat_platform_models",
    foreignKeys = [
        ForeignKey(
            entity = PlatformV2::class,
            parentColumns = ["id"],
            childColumns = ["platformId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["platformId"]),
        Index(value = ["modelId"])
    ]
)
data class ChatPlatformModelV2(
    @PrimaryKey
    val id: String,
    val platformId: String,
    val modelId: String,
    val displayName: String,
    val description: String? = null,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val supportsStreaming: Boolean = true,
    val inputCostPer1k: Double? = null,
    val outputCostPer1k: Double? = null,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
