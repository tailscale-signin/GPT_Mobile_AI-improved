package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "tool_connections")
@Serializable
data class ToolConnection(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "server_id")
    val serverId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "server_type")
    val serverType: String,

    @ColumnInfo(name = "url")
    val url: String? = null,

    @ColumnInfo(name = "api_key")
    val apiKey: String? = null,

    @ColumnInfo(name = "command")
    val command: String? = null,

    @ColumnInfo(name = "args")
    val args: String? = null,

    @ColumnInfo(name = "env")
    val env: String? = null,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
