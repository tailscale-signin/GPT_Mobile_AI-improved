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

@Dao
internal interface BenchmarkDao {
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

    @Transaction
    suspend fun restoreAll(runs: List<BenchmarkRunRecord>, snapshot: BenchmarkSnapshotRecord, trials: List<RuntimeTrialRecord>, qualifications: List<RuntimeQualificationRecord>, decisions: List<RuntimeDecisionRecord>) {
        replaceDecisions(decisions)
        publish(runs, snapshot)
        replaceRuntime(trials, qualifications)
    }

    @Query("SELECT * FROM benchmark_runs ORDER BY startedAt DESC")
    suspend fun runs(): List<BenchmarkRunRecord>

    @Query("SELECT * FROM benchmark_snapshots ORDER BY generation DESC")
    suspend fun snapshots(): List<BenchmarkSnapshotRecord>

    @Query("SELECT * FROM benchmark_snapshots WHERE generation = (SELECT generation FROM benchmark_current WHERE id = 1)")
    suspend fun current(): BenchmarkSnapshotRecord?

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
        removeOtherRuns(runs.map { it.id })
        insertSnapshot(snapshot)
        pointTo(BenchmarkCurrentRecord(generation = snapshot.generation))
    }
}

/** Separate additive database leaves existing chat schema/migrations and legacy history intact. */
@Database(entities = [BenchmarkRunRecord::class, BenchmarkSnapshotRecord::class, BenchmarkCurrentRecord::class, RuntimeTrialRecord::class, RuntimeQualificationRecord::class, RuntimeDecisionRecord::class], version = 2, exportSchema = true)
internal abstract class BenchmarkDatabase : RoomDatabase() {
    abstract fun dao(): BenchmarkDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_decisions` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_trials` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_qualifications` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
