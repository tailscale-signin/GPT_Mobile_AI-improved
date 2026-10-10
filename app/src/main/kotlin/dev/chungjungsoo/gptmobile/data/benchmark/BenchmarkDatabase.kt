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

@Dao
internal interface BenchmarkDao {
    @Query("SELECT * FROM benchmark_runs ORDER BY startedAt DESC")
    suspend fun runs(): List<BenchmarkRunRecord>

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
@Database(entities = [BenchmarkRunRecord::class, BenchmarkSnapshotRecord::class, BenchmarkCurrentRecord::class], version = 1, exportSchema = true)
internal abstract class BenchmarkDatabase : RoomDatabase() {
    abstract fun dao(): BenchmarkDao
}
