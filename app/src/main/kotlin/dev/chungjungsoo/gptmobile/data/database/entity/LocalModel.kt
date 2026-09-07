package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelRecord
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelStatus
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "local_models")
data class LocalModel(
    @PrimaryKey
    @ColumnInfo(name = "catalogEntryId")
    val catalogEntryId: String,

    @ColumnInfo(name = "commitHash")
    val commitHash: String,

    @ColumnInfo(name = "fileName")
    val fileName: String,

    @ColumnInfo(name = "relativeDirectory")
    val relativeDirectory: String,

    @ColumnInfo(name = "totalBytes")
    val totalBytes: Long,

    @ColumnInfo(name = "status")
    val status: String = LocalModelStatus.DOWNLOADING,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    fun toRecord(): LocalModelRecord = LocalModelRecord(
        catalogEntryId = catalogEntryId,
        commitHash = commitHash,
        fileName = fileName,
        relativeDirectory = relativeDirectory,
        status = status
    )
}
