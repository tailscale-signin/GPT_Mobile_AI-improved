package dev.chungjungsoo.gptmobile.data.research.telemetry

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "research_statistics_epoch")
data class ResearchStatisticsEpoch(@PrimaryKey val id: Int = 1, val clearedAt: Long = 0)

/** Compact durable producer receipts survive raw-file pruning; SQL derives exact aggregates. */
@Dao
interface ResearchStatisticsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: ResearchAttempt): Long

    @Transaction
    fun record(event: ResearchAttempt): Long {
        require(event.eventId.matches(Regex("[a-zA-Z0-9-]{1,80}")) && event.engineId.length in 1..180 && event.operation.length in 1..80)
        require(event.durationMs >= 0 && event.recordedAt >= 0 && event.schemaVersion in 1..2)
        if (event.recordedAt < (clearedAt() ?: 0)) return -1
        return insert(event)
    }

    @Transaction
    fun recordBatch(events: List<ResearchAttempt>) {
        events.forEach { record(it) }
    }

    @Query("SELECT MAX(clearedAt) FROM research_statistics_epoch")
    fun clearedAt(): Long?

    @Upsert
    fun putEpoch(value: ResearchStatisticsEpoch)

    @Query("DELETE FROM research_operation_receipts WHERE recordedAt<:before")
    fun deleteBefore(before: Long)

    @Transaction
    fun clearEpoch(value: ResearchStatisticsEpoch) {
        val boundary = maxOf(value.clearedAt, clearedAt() ?: 0)
        putEpoch(value.copy(clearedAt = boundary))
        deleteBefore(boundary)
    }

    @Transaction
    fun writeSnapshot(writer: java.io.Writer) {
        writer.write(kotlinx.serialization.json.Json.encodeToString(ResearchStatisticsHeader.serializer(), ResearchStatisticsHeader(clearedAt = clearedAt() ?: 0)) + "\n")
        var offset = 0
        while (true) {
            val rows = page(offset)
            rows.forEach { writer.write(kotlinx.serialization.json.Json.encodeToString(ResearchAttempt.serializer(), it) + "\n") }
            if (rows.size < 5000) break
            offset += rows.size
        }
    }

    @Query(
        """SELECT
        COALESCE(SUM(CASE WHEN NOT(schemaVersion<2 AND operation IN ('lookup_search','read')) AND dispatched=1 AND cacheHit=0 AND outcome IN ('SUCCESS','EMPTY','FAILED','INVALID_RESPONSE') THEN 1 ELSE 0 END),0) AS attempts,
        COALESCE(SUM(CASE WHEN NOT(schemaVersion<2 AND operation IN ('lookup_search','read')) AND dispatched=1 AND cacheHit=0 AND outcome='SUCCESS' THEN 1 ELSE 0 END),0) AS successes,
        COALESCE(SUM(CASE WHEN NOT(schemaVersion<2 AND operation IN ('lookup_search','read')) AND dispatched=1 AND cacheHit=0 AND outcome IN ('FAILED','INVALID_RESPONSE') THEN 1 ELSE 0 END),0) AS failures,
        COALESCE(SUM(CASE WHEN cacheHit=1 THEN 1 ELSE 0 END),0) AS cached,
        COALESCE(SUM(CASE WHEN dispatched=0 AND cacheHit=0 THEN 1 ELSE 0 END),0) AS skipped,
        COALESCE(SUM(CASE WHEN NOT(schemaVersion<2 AND operation IN ('lookup_search','read')) AND dispatched=1 AND cacheHit=0 AND outcome='EMPTY' THEN 1 ELSE 0 END),0) AS empty,
        COALESCE(SUM(CASE WHEN NOT(schemaVersion<2 AND operation IN ('lookup_search','read')) AND dispatched=1 AND cacheHit=0 AND outcome='CANCELLED' THEN 1 ELSE 0 END),0) AS canceled,
        COALESCE(SUM(CASE WHEN outcome='TRANSPORT_SUCCESS' OR schemaVersion<2 AND operation IN ('lookup_search','read') AND outcome='SUCCESS' THEN 1 ELSE 0 END),0) AS transportSuccesses,
        COALESCE(SUM(CASE WHEN outcome='TRANSPORT_FAILED' OR schemaVersion<2 AND operation IN ('lookup_search','read') AND outcome='FAILED' THEN 1 ELSE 0 END),0) AS transportFailures,
        COALESCE(SUM(CASE WHEN outcome='TRANSPORT_CANCELLED' OR schemaVersion<2 AND operation IN ('lookup_search','read') AND outcome='CANCELLED' THEN 1 ELSE 0 END),0) AS transportCanceled
        FROM research_operation_receipts WHERE engineId=:engine AND recordedAt>=:since"""
    )
    fun rollup(engine: String, since: Long): ResearchEngineRollup

    @Query("SELECT COUNT(*) FROM research_operation_receipts WHERE engineId=:engine AND recordedAt>=:since AND outcome='SUCCESS' AND schemaVersion=2 AND cacheHit=0 AND dispatched=1 AND durationMs>0")
    fun latencyCount(engine: String, since: Long): Long

    @Query("SELECT durationMs FROM research_operation_receipts WHERE engineId=:engine AND recordedAt>=:since AND outcome='SUCCESS' AND schemaVersion=2 AND cacheHit=0 AND dispatched=1 AND durationMs>0 ORDER BY durationMs LIMIT 1 OFFSET :offset")
    fun latencyAt(engine: String, since: Long, offset: Long): Long?

    @Query("SELECT * FROM research_operation_receipts ORDER BY eventId LIMIT 5000 OFFSET :offset")
    fun page(offset: Int): List<ResearchAttempt>

    @Query("SELECT * FROM research_operation_receipts WHERE engineId=:engine ORDER BY recordedAt DESC LIMIT 60")
    fun recent(engine: String): List<ResearchAttempt>
}

@Serializable
data class ResearchStatisticsHeader(val schemaVersion: Int = 1, val definitionVersion: Int = 2, val clearedAt: Long = 0)
