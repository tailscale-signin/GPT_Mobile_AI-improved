package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.chungjungsoo.gptmobile.data.database.entity.OpenRouterBatchCacheEntity

@Dao
interface OpenRouterBatchCacheDao {

    @Query("SELECT * FROM openrouter_batch_cache WHERE cache_key = :cacheKey LIMIT 1")
    suspend fun getByCacheKey(cacheKey: String): OpenRouterBatchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: OpenRouterBatchCacheEntity): Long

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateBatch(entities: List<OpenRouterBatchCacheEntity>): List<Long>

    @Query("DELETE FROM openrouter_batch_cache WHERE id IN (SELECT id FROM openrouter_batch_cache WHERE timestamp < :threshold LIMIT :limit)")
    suspend fun deleteExpiredBatch(threshold: Long, limit: Int = 100): Int

    @Query("DELETE FROM openrouter_batch_cache WHERE timestamp < :threshold")
    suspend fun deleteExpired(threshold: Long): Int

    @Query("DELETE FROM openrouter_batch_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM openrouter_batch_cache")
    suspend fun count(): Int
}
