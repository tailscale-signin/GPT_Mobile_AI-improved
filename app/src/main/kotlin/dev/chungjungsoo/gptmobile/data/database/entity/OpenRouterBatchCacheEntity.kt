package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "openrouter_batch_cache",
    indices = [
        Index(value = ["cache_key"], unique = true),
        Index(value = ["timestamp"])
    ]
)
data class OpenRouterBatchCacheEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "cache_key")
    val cacheKey: String,

    @ColumnInfo(name = "response_content")
    val responseContent: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
