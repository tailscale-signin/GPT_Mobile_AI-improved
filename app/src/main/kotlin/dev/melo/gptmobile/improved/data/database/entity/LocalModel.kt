package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_models")
data class LocalModel(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = "",

    @ColumnInfo(name = "display_name")
    val displayName: String = "",

    @ColumnInfo(name = "model_name")
    val modelName: String = "",

    @ColumnInfo(name = "file_path")
    val filePath: String = "",

    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,

    @ColumnInfo(name = "download_progress")
    val downloadProgress: Float = 0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000
)
