package com.example.gptmobileai.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chat_rooms",
    indices = [
        Index(value = ["title"]),
        Index(value = ["createdAt"])
    ]
)
data class ChatRoomV2(
    @PrimaryKey
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
