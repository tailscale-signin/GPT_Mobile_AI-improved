package com.example.gptmobileai.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "tool_connections")
data class ToolConnection(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String,
    val configJson: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
