package dev.chungjungsoo.gptmobile.data.benchmark

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** Complete nested raw trials are serialized without rounding or lossy column projection. */
@Entity(tableName = "benchmark_runs")
internal data class BenchmarkRunRecord(@PrimaryKey val id: String, val startedAt: Long, val payload: String)

@Entity(tableName = "benchmark_snapshots")
internal data class BenchmarkSnapshotRecord(@PrimaryKey val generation: Long, val createdAt: Long, val payload: String)

@Entity(tableName = "benchmark_current")
internal data class BenchmarkCurrentRecord(@PrimaryKey val id: Int = 1, val generation: Long)

@Entity(tableName = "runtime_trials")
internal data class RuntimeTrialRecord(@PrimaryKey val id: String, val timestamp: Long, val payload: String)

@Entity(tableName = "runtime_qualifications")
internal data class RuntimeQualificationRecord(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "runtime_decisions")
internal data class RuntimeDecisionRecord(@PrimaryKey val id: String, val timestamp: Long, val payload: String)

@Entity(tableName = "benchmark_batches")
internal data class BenchmarkBatchRecord(@PrimaryKey val id: String, val createdAt: Long, val state: String, val payload: String)

@Entity(tableName = "benchmark_queue", primaryKeys = ["batchId", "ordinal"])
internal data class BenchmarkQueueRecord(val batchId: String, val ordinal: Int, val runId: String, val state: String, val payload: String)

@Entity(tableName = "benchmark_attempts")
internal data class BenchmarkAttemptRecord(@PrimaryKey val id: String, val batchId: String, val ordinal: Int, val caseId: String, val payload: String)

@Entity(tableName = "benchmark_batch_events", primaryKeys = ["batchId", "sequence"])
internal data class BenchmarkEventRecord(val batchId: String, val sequence: Long, val payload: String)

@Dao
internal interface BenchmarkDao {
    @Query("SELECT * FROM benchmark_batches ORDER BY createdAt DESC")
    suspend fun batches(): List<BenchmarkBatchRecord>

    @Query("SELECT * FROM benchmark_attempts WHERE batchId = :batchId ORDER BY ordinal")
    suspend fun attempts(batchId: String): List<BenchmarkAttemptRecord>

    @Query("SELECT * FROM benchmark_batch_events WHERE batchId = :batchId ORDER BY sequence")
    suspend fun events(batchId: String): List<BenchmarkEventRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(batch: BenchmarkBatchRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueue(items: List<BenchmarkQueueRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttempts(attempts: List<BenchmarkAttemptRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: BenchmarkEventRecord)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM benchmark_batch_events WHERE batchId = :batchId")
    suspend fun nextSequence(batchId: String): Long

    @Query("DELETE FROM benchmark_runs WHERE id = :id")
    suspend fun deleteRun(id: String)

    @Query("DELETE FROM benchmark_snapshots WHERE generation IN (SELECT generation FROM benchmark_snapshots ORDER BY generation DESC LIMIT -1 OFFSET 100)")
    suspend fun pruneSnapshots()

    @Transaction
    suspend fun checkpoint(batch: BenchmarkBatchRecord, queue: List<BenchmarkQueueRecord>, run: BenchmarkRunRecord?, attempts: List<BenchmarkAttemptRecord>, event: BenchmarkEventRecord) {
        upsertBatch(batch)
        upsertQueue(queue)
        if (run != null) insertRuns(listOf(run))
        upsertAttempts(attempts)
        insertEvent(event)
    }

    @Query("SELECT * FROM runtime_decisions ORDER BY timestamp DESC")
    suspend fun runtimeDecisions(): List<RuntimeDecisionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecisions(records: List<RuntimeDecisionRecord>)

    @Query("DELETE FROM runtime_decisions")
    suspend fun clearDecisions()

    @Transaction
    suspend fun replaceDecisions(records: List<RuntimeDecisionRecord>) {
        clearDecisions()
        insertDecisions(records)
    }

    @Query("DELETE FROM runtime_trials WHERE id IN (SELECT id FROM runtime_trials ORDER BY timestamp DESC, id ASC LIMIT -1 OFFSET :limit)")
    suspend fun pruneTrials(limit: Int)

    @Query("DELETE FROM runtime_qualifications WHERE id IN (:ids)")
    suspend fun deleteQualifications(ids: List<String>)

    @Transaction
    suspend fun appendRuntimeTrial(trial: RuntimeTrialRecord, limit: Int, invalidated: List<String>) {
        insertRuntimeTrials(listOf(trial))
        pruneTrials(limit)
        invalidated.chunked(500).forEach { deleteQualifications(it) }
    }

    @Query("SELECT * FROM runtime_trials ORDER BY timestamp DESC, id ASC")
    suspend fun runtimeTrials(): List<RuntimeTrialRecord>

    @Query("SELECT * FROM runtime_qualifications")
    suspend fun runtimeQualifications(): List<RuntimeQualificationRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuntimeTrials(trials: List<RuntimeTrialRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQualifications(records: List<RuntimeQualificationRecord>)

    @Query("DELETE FROM runtime_trials")
    suspend fun clearRuntimeTrials()

    @Query("DELETE FROM runtime_qualifications")
    suspend fun clearQualifications()

    @Transaction
    suspend fun replaceRuntime(trials: List<RuntimeTrialRecord>, qualifications: List<RuntimeQualificationRecord>) {
        clearRuntimeTrials()
        clearQualifications()
        insertRuntimeTrials(trials)
        insertQualifications(qualifications)
    }

    @Query("DELETE FROM benchmark_batches")
    suspend fun clearBatches()

    @Query("DELETE FROM benchmark_queue")
    suspend fun clearQueue()

    @Query("DELETE FROM benchmark_attempts")
    suspend fun clearAttempts()

    @Query("DELETE FROM benchmark_batch_events")
    suspend fun clearBatchEvents()

    @Transaction
    suspend fun restoreAll(runs: List<BenchmarkRunRecord>, snapshot: BenchmarkSnapshotRecord, trials: List<RuntimeTrialRecord>, qualifications: List<RuntimeQualificationRecord>, decisions: List<RuntimeDecisionRecord>, batches: List<BenchmarkBatchRecord> = emptyList(), queue: List<BenchmarkQueueRecord> = emptyList(), attempts: List<BenchmarkAttemptRecord> = emptyList(), events: List<BenchmarkEventRecord> = emptyList()) {
        replaceDecisions(decisions)
        removeOtherRuns(runs.map { it.id })
        publish(runs, snapshot)
        replaceRuntime(trials, qualifications)
        clearBatches()
        clearQueue()
        clearAttempts()
        clearBatchEvents()
        batches.forEach { upsertBatch(it) }
        upsertQueue(queue)
        upsertAttempts(attempts)
        events.forEach { insertEvent(it) }
    }

    @Query("SELECT * FROM benchmark_runs ORDER BY startedAt DESC")
    suspend fun runs(): List<BenchmarkRunRecord>

    @Query("SELECT * FROM benchmark_snapshots ORDER BY generation DESC")
    suspend fun snapshots(): List<BenchmarkSnapshotRecord>

    @Query("SELECT * FROM benchmark_snapshots WHERE generation = (SELECT generation FROM benchmark_current WHERE id = 1)")
    suspend fun current(): BenchmarkSnapshotRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM benchmark_current WHERE id = 1)")
    suspend fun canonicalInitialized(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuns(runs: List<BenchmarkRunRecord>)

    @Insert
    suspend fun insertSnapshot(snapshot: BenchmarkSnapshotRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun pointTo(pointer: BenchmarkCurrentRecord)

    @Query("DELETE FROM benchmark_runs WHERE id NOT IN (:ids)")
    suspend fun removeOtherRuns(ids: List<String>)

    @Transaction
    suspend fun publish(runs: List<BenchmarkRunRecord>, snapshot: BenchmarkSnapshotRecord) {
        insertRuns(runs)
        insertSnapshot(snapshot)
        pruneSnapshots()
        pointTo(BenchmarkCurrentRecord(generation = snapshot.generation))
    }
}

/** Separate additive database leaves existing chat schema/migrations and legacy history intact. */
@Database(entities = [BenchmarkRunRecord::class, BenchmarkSnapshotRecord::class, BenchmarkCurrentRecord::class, RuntimeTrialRecord::class, RuntimeQualificationRecord::class, RuntimeDecisionRecord::class, BenchmarkBatchRecord::class, BenchmarkQueueRecord::class, BenchmarkAttemptRecord::class, BenchmarkEventRecord::class], version = 3, exportSchema = true)
internal abstract class BenchmarkDatabase : RoomDatabase() {
    abstract fun dao(): BenchmarkDao

    companion object {
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `benchmark_batches` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `benchmark_queue` (`batchId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `runId` TEXT NOT NULL, `state` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`batchId`, `ordinal`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `benchmark_attempts` (`id` TEXT NOT NULL, `batchId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `caseId` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `benchmark_batch_events` (`batchId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`batchId`, `sequence`))")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_decisions` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_trials` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_qualifications` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
