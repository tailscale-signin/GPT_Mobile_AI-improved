package dev.chungjungsoo.gptmobile.data.agent.tool

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Dedicated database avoids changing the existing application Room schema and migrations. */
@Entity(tableName = "engineering_tasks")
internal data class EngineeringTaskRow(
    @PrimaryKey val taskId: String,
    val repositoryOwner: String,
    val repositoryName: String,
    val workingBranch: String,
    val baseSha: String,
    val requiresHardwareVerification: Boolean = false,
    val requiresHumanApproval: Boolean = true,
    val state: String = EngineeringState.CREATED.name,
    val sequence: Long = 0,
    val attempt: Int = 1,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "engineering_events",
    foreignKeys = [
        ForeignKey(
            entity = EngineeringTaskRow::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["taskId", "sequence"], unique = true)],
)
internal data class EngineeringEventRow(
    @PrimaryKey val eventId: String,
    val taskId: String,
    val sequence: Long,
    val attempt: Int,
    val previousState: String,
    val evidence: String,
    val resultingState: String,
    val evidenceDigest: String,
    val recordedAtEpochMs: Long,
)

@Entity(
    tableName = "engineering_git_operations",
    foreignKeys = [
        ForeignKey(
            entity = EngineeringTaskRow::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("taskId"), Index(value = ["idempotencyToken"], unique = true)],
)
internal data class EngineeringGitOperationRow(
    @PrimaryKey val operationId: String,
    val taskId: String,
    val idempotencyToken: String,
    val expectedHeadSha: String,
    val stage: String = GitSagaStage.PENDING.name,
    val blobManifestDigest: String? = null,
    val treeSha: String? = null,
    val commitSha: String? = null,
    val remoteRefSha: String? = null,
    val updatedAtEpochMs: Long,
)

@Dao
internal interface EngineeringLedgerDao {
    @Query("SELECT * FROM engineering_tasks WHERE taskId = :taskId")
    suspend fun task(taskId: String): EngineeringTaskRow?

    @Query("SELECT * FROM engineering_events WHERE taskId = :taskId ORDER BY sequence ASC")
    suspend fun events(taskId: String): List<EngineeringEventRow>

    @Query("SELECT * FROM engineering_git_operations WHERE operationId = :operationId")
    suspend fun operation(operationId: String): EngineeringGitOperationRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(row: EngineeringTaskRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(row: EngineeringEventRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(row: EngineeringGitOperationRow)

    @Query(
        "UPDATE engineering_tasks SET state = :state, sequence = :sequence, " +
            "updatedAtEpochMs = :updatedAt WHERE taskId = :taskId AND sequence = :expectedSequence",
    )
    suspend fun updateTask(
        taskId: String,
        expectedSequence: Long,
        sequence: Long,
        state: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE engineering_git_operations SET stage = :stage, treeSha = :treeSha, " +
            "commitSha = :commitSha, remoteRefSha = :remoteRefSha, updatedAtEpochMs = :updatedAt " +
            "WHERE operationId = :operationId AND stage = :expectedStage",
    )
    suspend fun updateOperation(
        operationId: String,
        expectedStage: String,
        stage: String,
        treeSha: String?,
        commitSha: String?,
        remoteRefSha: String?,
        updatedAt: Long,
    ): Int
}

@Database(
    entities = [
        EngineeringTaskRow::class,
        EngineeringEventRow::class,
        EngineeringGitOperationRow::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class GitHubEngineeringDatabase : RoomDatabase() {
    abstract fun ledgerDao(): EngineeringLedgerDao
}

/** All state projections and evidence inserts succeed or roll back together. */
internal class GitHubEngineeringRoomLedger(
    private val database: GitHubEngineeringDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun createTask(row: EngineeringTaskRow) {
        require(row.state == EngineeringState.CREATED.name && row.sequence == 0L && row.attempt == 1)
        require(row.repositoryOwner.isNotBlank() && row.repositoryName.isNotBlank())
        require(row.workingBranch.isNotBlank())
        database.ledgerDao().insertTask(row)
    }

    suspend fun append(eventId: String, event: EngineeringLedgerEvent) {
        database.withTransaction {
            val task = requireNotNull(database.ledgerDao().task(event.taskId)) { "Unknown engineering task" }
            val current = EngineeringLedgerSnapshot(
                taskId = task.taskId,
                sequence = task.sequence,
                attempt = task.attempt,
                state = EngineeringState.valueOf(task.state),
            )
            val next = GitHubEngineeringLedger.append(current, event)
            database.ledgerDao().insertEvent(
                EngineeringEventRow(
                    eventId = eventId,
                    taskId = event.taskId,
                    sequence = event.sequence,
                    attempt = event.attempt,
                    previousState = event.previousState.name,
                    evidence = event.evidence.name,
                    resultingState = next.state.name,
                    evidenceDigest = event.evidenceDigest,
                    recordedAtEpochMs = clock(),
                ),
            )
            check(
                database.ledgerDao().updateTask(
                    taskId = task.taskId,
                    expectedSequence = task.sequence,
                    sequence = next.sequence,
                    state = next.state.name,
                    updatedAt = clock(),
                ) == 1,
            ) { "Concurrent task modification" }
        }
    }
}
