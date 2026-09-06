package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.melo.gptmobile.improved.data.model.ClientType

@Entity(tableName = "platforms_v2")
data class PlatformV2(
    @PrimaryKey
    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "client_type")
    val clientType: ClientType,

    @ColumnInfo(name = "api_key")
    val apiKey: String,

    @ColumnInfo(name = "api_url")
    val apiUrl: String,

    @ColumnInfo(name = "default_model")
    val defaultModel: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false
)
