package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_models")
data class LocalModel(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "model_id")
    val modelId: Int = 0,

    @ColumnInfo(name = "model_name")
    val modelName: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000
)
