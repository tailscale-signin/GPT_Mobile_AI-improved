package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "local_models")
@Serializable
data class LocalModel(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "filename")
    val filename: String,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "sha256")
    val sha256: String = "",

    @ColumnInfo(name = "context_length")
    val contextLength: Int = 2048,

    @ColumnInfo(name = "download_status")
    val downloadStatus: String = "NOT_DOWNLOADED",

    @ColumnInfo(name = "download_progress")
    val downloadProgress: Float = 0f,

    @ColumnInfo(name = "local_path")
    val localPath: String? = null,

    @ColumnInfo(name = "download_id")
    val downloadId: Long? = null,

    @ColumnInfo(name = "description")
    val description: String = ""
)
