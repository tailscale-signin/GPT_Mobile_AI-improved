package dev.chungjungsoo.gptmobile.data.telemetry.tool

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Database(entities = [ToolSubject::class, ToolInvocation::class, ToolRollup::class, ToolAttempt::class, ToolHealthObservation::class], version = 1, exportSchema = true)
abstract class ToolStatisticsDatabase : RoomDatabase() {
    abstract fun statistics(): ToolStatisticsDao
}

@Dao
interface ToolStatisticsDao {
    @Query("SELECT * FROM tool_health WHERE connection = :uid")
    fun health(uid: String): Flow<ToolHealthObservation?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolHealthObservation)

    @Query("SELECT * FROM tool_subject WHERE `key` = :key")
    suspend fun subject(key: String): ToolSubject?

    @Query("SELECT * FROM tool_subject")
    suspend fun subjects(): List<ToolSubject>

    @Query("SELECT * FROM tool_subject WHERE plugin = :plugin ORDER BY capability")
    fun observeSubjects(plugin: String): Flow<List<ToolSubject>>

    @Query("SELECT * FROM tool_invocation WHERE id = :id")
    suspend fun invocation(id: String): ToolInvocation?

    @Query("SELECT * FROM tool_invocation")
    suspend fun invocations(): List<ToolInvocation>

    @Query("SELECT * FROM tool_invocation WHERE outcome = 'IN_PROGRESS'")
    suspend fun interrupted(): List<ToolInvocation>

    @Query("SELECT i.* FROM tool_invocation i INNER JOIN tool_subject s ON i.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND i.detailRetained = 1 ORDER BY i.startedAt DESC LIMIT :limit OFFSET :offset")
    fun activity(plugin: String, limit: Int = 50, offset: Int = 0): Flow<List<ToolInvocation>>

    @Query("SELECT * FROM tool_rollup WHERE subject = :subject AND bucket = :bucket")
    suspend fun rollup(subject: String, bucket: Long): ToolRollup?

    @Query("SELECT r.* FROM tool_rollup r INNER JOIN tool_subject s ON r.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND r.bucket = -1")
    fun summary(plugin: String): Flow<List<ToolRollup>>

    @Query("SELECT r.* FROM tool_rollup r INNER JOIN tool_subject s ON r.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND r.bucket >= :since ORDER BY r.bucket")
    fun chart(plugin: String, since: Long): Flow<List<ToolRollup>>

    @Query("SELECT * FROM tool_rollup")
    suspend fun rollups(): List<ToolRollup>

    @Query("SELECT * FROM tool_attempt")
    suspend fun attempts(): List<ToolAttempt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolSubject)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolInvocation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolRollup)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(value: ToolAttempt)

    @Query("DELETE FROM tool_invocation WHERE startedAt < :before AND outcome NOT IN ('IN_PROGRESS', 'UNKNOWN')")
    suspend fun prune(before: Long)

    @Query("UPDATE tool_invocation SET profile = 'redacted', root = 'redacted', parent = NULL, owner = 'redacted', detailRetained = 0 WHERE startedAt < :before AND outcome = 'UNKNOWN'")
    suspend fun redactUnresolved(before: Long)

    @Query("DELETE FROM tool_rollup WHERE bucket >= 0 AND bucket < :before")
    suspend fun pruneHourly(before: Long)

    @Query("DELETE FROM tool_attempt WHERE invocation NOT IN (SELECT id FROM tool_invocation WHERE detailRetained = 1)")
    suspend fun pruneAttempts()

    @Query("DELETE FROM tool_rollup WHERE subject = :key")
    suspend fun clearRollups(key: String)

    @Query("DELETE FROM tool_attempt WHERE invocation IN (SELECT id FROM tool_invocation WHERE subject = :key)")
    suspend fun clearAttempts(key: String)

    @Query("DELETE FROM tool_invocation WHERE subject = :key")
    suspend fun clearInvocations(key: String)

    @Query("DELETE FROM tool_invocation WHERE id = :id")
    suspend fun removeInvocation(id: String)

    @Query("DELETE FROM tool_subject")
    suspend fun clearSubjects()

    @Query("DELETE FROM tool_rollup")
    suspend fun clearRollups()

    @Query("DELETE FROM tool_invocation")
    suspend fun clearInvocations()

    @Query("DELETE FROM tool_attempt")
    suspend fun clearAttempts()
}
