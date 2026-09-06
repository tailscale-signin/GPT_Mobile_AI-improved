package com.example.gptmobileai.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "local_models")
data class LocalModel(
    @PrimaryKey
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val format: String,
    val quantization: String?,
    val contextLength: Int,
    val parameters: Long?,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
)
