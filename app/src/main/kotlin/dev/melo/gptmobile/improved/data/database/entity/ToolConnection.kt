package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tool_connections")
data class ToolConnection(
    @PrimaryKey
    @ColumnInfo(name = "connection_uid")
    val connectionUid: String,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "alias")
    val alias: String = "",

    @ColumnInfo(name = "type")
    val type: String = ToolConnectionType.MCP,

    @ColumnInfo(name = "server_name")
    val serverName: String = "",

    @ColumnInfo(name = "transport_type")
    val transportType: String = "",

    @ColumnInfo(name = "endpoint_url")
    val endpointUrl: String = "",

    @ColumnInfo(name = "secret_ref")
    val secretRef: String? = null,

    @ColumnInfo(name = "headers")
    val headers: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000
)

object ToolConnectionType {
    const val MCP = "mcp"
    const val FIRECRAWL = "firecrawl"
    const val PERPLEXITY = "perplexity"
    const val EXA = "exa"
}
