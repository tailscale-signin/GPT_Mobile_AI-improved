package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.melo.gptmobile.improved.data.localmodel.LocalModelRecord
import dev.melo.gptmobile.improved.data.localmodel.LocalModelStatus

@Entity(tableName = "local_models")
data class LocalModel(
    @PrimaryKey
    @ColumnInfo(name = "catalog_entry_id")
    val catalogEntryId: String = "",

    @ColumnInfo(name = "commit_hash")
    val commitHash: String = "",

    @ColumnInfo(name = "file_name")
    val fileName: String = "",

    @ColumnInfo(name = "relative_directory")
    val relativeDirectory: String = "",

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = 0L,

    @ColumnInfo(name = "status")
    val status: String = LocalModelStatus.NOT_DOWNLOADED,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    val id: String
        get() = catalogEntryId

    val isDownloaded: Boolean
        get() = status == LocalModelStatus.READY

    fun toRecord(): LocalModelRecord = LocalModelRecord(
        catalogEntryId = catalogEntryId,
        commitHash = commitHash,
        fileName = fileName,
        relativeDirectory = relativeDirectory,
        status = status
    )
}
