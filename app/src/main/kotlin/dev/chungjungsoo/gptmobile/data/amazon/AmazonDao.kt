package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AmazonDao {
    @Query("SELECT * FROM amazon_request_budget WHERE id = 1")
    suspend fun budget(): AmazonBudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBudget(value: AmazonBudgetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addObservation(value: AmazonObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCheckEvent(value: AmazonCheckEventEntity)

    @Query("SELECT * FROM amazon_observations WHERE ownerProfileUid = :owner AND marketplace = :market AND asin = :asin ORDER BY observedAt DESC, id LIMIT :limit")
    suspend fun history(owner: String, market: String, asin: String, limit: Int): List<AmazonObservationEntity>

    @Query("SELECT COUNT(*) FROM amazon_observations WHERE ownerProfileUid = :owner AND marketplace = :market AND asin = :asin")
    suspend fun historyCount(owner: String, market: String, asin: String): Int

    @Query("SELECT * FROM amazon_check_events WHERE ownerProfileUid = :owner AND marketplace = :market AND asin = :asin ORDER BY attemptedAt DESC, id LIMIT :limit")
    suspend fun checkEvents(owner: String, market: String, asin: String, limit: Int): List<AmazonCheckEventEntity>

    @Query("SELECT * FROM amazon_watches WHERE ownerProfileUid = :owner ORDER BY createdAt DESC, id LIMIT 20")
    suspend fun watches(owner: String): List<AmazonWatchEntity>

    @Query("SELECT * FROM amazon_watches ORDER BY createdAt DESC, id")
    fun observeWatches(): Flow<List<AmazonWatchEntity>>

    @Query("SELECT * FROM amazon_watches WHERE id = :id AND ownerProfileUid = :owner")
    suspend fun watch(owner: String, id: String): AmazonWatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWatch(value: AmazonWatchEntity)

    @Query("DELETE FROM amazon_watches WHERE id = :id AND ownerProfileUid = :owner AND generation = :generation")
    suspend fun deleteWatch(owner: String, id: String, generation: Int): Int

    @Query("DELETE FROM amazon_observations WHERE ownerProfileUid = :owner")
    suspend fun clearHistory(owner: String)

    @Query("DELETE FROM amazon_check_events WHERE ownerProfileUid = :owner")
    suspend fun clearChecks(owner: String)

    @Query("DELETE FROM amazon_check_events WHERE attemptedAt < :before")
    suspend fun pruneChecks(before: Long)

    // Preserve the latest point of each series belonging to an unpaused watch.
    @Query("DELETE FROM amazon_observations WHERE observedAt < :before AND id NOT IN (SELECT o.id FROM amazon_observations o WHERE EXISTS (SELECT 1 FROM amazon_watches w WHERE w.ownerProfileUid = o.ownerProfileUid AND w.marketplace = o.marketplace AND w.asin = o.asin AND w.state != 'PAUSED') AND NOT EXISTS (SELECT 1 FROM amazon_observations newer WHERE newer.ownerProfileUid = o.ownerProfileUid AND newer.seriesKey = o.seriesKey AND (newer.observedAt > o.observedAt OR (newer.observedAt = o.observedAt AND newer.id > o.id))))")
    suspend fun pruneHistory(before: Long)

    @Query("DELETE FROM amazon_observations WHERE id IN (SELECT o.id FROM amazon_observations o WHERE NOT (EXISTS (SELECT 1 FROM amazon_watches w WHERE w.ownerProfileUid = o.ownerProfileUid AND w.marketplace = o.marketplace AND w.asin = o.asin AND w.state != 'PAUSED') AND NOT EXISTS (SELECT 1 FROM amazon_observations newer WHERE newer.ownerProfileUid = o.ownerProfileUid AND newer.seriesKey = o.seriesKey AND (newer.observedAt > o.observedAt OR (newer.observedAt = o.observedAt AND newer.id > o.id)))) ORDER BY o.observedAt ASC, o.id LIMIT MAX(0, (SELECT COUNT(*) FROM amazon_observations) - :cap))")
    suspend fun trimHistory(cap: Int)
}
