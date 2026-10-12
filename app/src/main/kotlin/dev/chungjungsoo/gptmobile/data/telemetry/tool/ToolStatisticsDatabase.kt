package dev.chungjungsoo.gptmobile.data.telemetry.tool

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Database(entities = [ToolSubject::class, ToolInvocation::class, ToolRollup::class, ToolAttempt::class, ToolHealthObservation::class, ToolStartReceipt::class], version = 2, exportSchema = true)
abstract class ToolStatisticsDatabase : RoomDatabase() {
    abstract fun statistics(): ToolStatisticsDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tool_invocation ADD COLUMN producerInvocation TEXT")
                // Existing totals are preserved as an explicitly unattributed dimension.
                db.execSQL("ALTER TABLE tool_rollup RENAME TO tool_rollup_legacy")
                db.execSQL("CREATE TABLE IF NOT EXISTS tool_rollup (subject TEXT NOT NULL, bucket INTEGER NOT NULL, total INTEGER NOT NULL, success INTEGER NOT NULL, failure INTEGER NOT NULL, partial INTEGER NOT NULL, canceled INTEGER NOT NULL, unknown INTEGER NOT NULL, inProgress INTEGER NOT NULL, shared INTEGER NOT NULL, lastUsed INTEGER NOT NULL, liveDurationMs INTEGER NOT NULL, liveSamples INTEGER NOT NULL, latencyBuckets TEXT NOT NULL, profile TEXT NOT NULL, PRIMARY KEY(subject, bucket, profile))")
                db.execSQL("INSERT INTO tool_rollup SELECT *, '' FROM tool_rollup_legacy")
                db.execSQL("DROP TABLE tool_rollup_legacy")
                db.execSQL("CREATE TABLE IF NOT EXISTS tool_start_receipt (id TEXT NOT NULL PRIMARY KEY, subject TEXT NOT NULL, epoch TEXT NOT NULL)")
                db.execSQL("INSERT INTO tool_start_receipt SELECT id, subject, epoch FROM tool_invocation")
            }
        }
    }
}

@Dao
interface ToolStatisticsDao {
    @Query("SELECT DISTINCT profile FROM tool_rollup WHERE profile != '' ORDER BY profile")
    fun profiles(): Flow<List<String>>

    @Query("SELECT * FROM tool_start_receipt WHERE id = :id")
    suspend fun receipt(id: String): ToolStartReceipt?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(value: ToolStartReceipt)

    @Query("SELECT a.* FROM tool_attempt a JOIN tool_invocation i ON a.invocation = i.id JOIN tool_subject s ON i.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND (:profile IS NULL OR i.profile = :profile) AND i.startedAt >= :fromMillis AND i.startedAt < :untilMillis ORDER BY a.observedAt DESC LIMIT :limit")
    fun observedAttempts(plugin: String, profile: String?, fromMillis: Long, untilMillis: Long, limit: Int): Flow<List<ToolAttempt>>

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

    @Query("SELECT i.* FROM tool_invocation i INNER JOIN tool_subject s ON i.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND i.detailRetained = 1 AND (:profile IS NULL OR i.profile = :profile) AND i.startedAt >= :fromMillis AND i.startedAt < :untilMillis AND (:outcome IS NULL OR i.outcome = :outcome) ORDER BY i.startedAt DESC LIMIT :limit OFFSET :offset")
    fun activity(plugin: String, limit: Int = 50, offset: Int = 0, profile: String? = null, fromMillis: Long = 0, untilMillis: Long = Long.MAX_VALUE, outcome: String? = null): Flow<List<ToolInvocation>>

    @Query("SELECT * FROM tool_rollup WHERE subject = :subject AND bucket = :bucket AND profile = :profile")
    suspend fun rollup(subject: String, bucket: Long, profile: String = ""): ToolRollup?

    @Query("SELECT r.* FROM tool_rollup r INNER JOIN tool_subject s ON r.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND (:profile IS NULL OR r.profile = :profile) AND ((:fromHour = -1 AND r.bucket = -1) OR (:fromHour >= 0 AND r.bucket >= :fromHour AND r.bucket < :untilHour))")
    fun summary(plugin: String, profile: String? = null, fromHour: Long = -1, untilHour: Long = Long.MAX_VALUE): Flow<List<ToolRollup>>

    @Query("SELECT r.* FROM tool_rollup r INNER JOIN tool_subject s ON r.subject = s.`key` WHERE (s.plugin = :plugin OR s.`key` = :plugin) AND r.bucket >= :since AND r.bucket < :untilHour AND (:profile IS NULL OR r.profile = :profile) ORDER BY r.bucket")
    fun chart(plugin: String, since: Long, profile: String? = null, untilHour: Long = Long.MAX_VALUE): Flow<List<ToolRollup>>

    @Query("SELECT * FROM tool_rollup")
    suspend fun rollups(): List<ToolRollup>

    @Query("UPDATE tool_attempt SET outcome = 'UNKNOWN' WHERE invocation = :id AND outcome = 'IN_PROGRESS'")
    suspend fun settleAttempts(id: String)

    @Query("SELECT * FROM tool_attempt")
    suspend fun attempts(): List<ToolAttempt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolSubject)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolInvocation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: ToolRollup)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
