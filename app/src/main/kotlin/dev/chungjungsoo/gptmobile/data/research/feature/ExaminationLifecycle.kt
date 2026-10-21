package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local execution history; restored evidence never restores a live worker lease. */
@kotlinx.serialization.Serializable
@Entity(tableName = "research_job_transitions", foreignKeys = [ForeignKey(entity = ExaminationJob::class, parentColumns = ["jobId"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)], indices = [Index("jobId")])
data class ExaminationTransition(@PrimaryKey(autoGenerate = true) val sequence: Long = 0, val jobId: String, val lease: Long, val stage: ExaminationStage, val at: Long, val workerId: String?, val reason: String?, val cancellationOrigin: String?)

internal object ExaminationLifecycle {
    val active = setOf(ExaminationStage.QUEUED, ExaminationStage.RESOLVING, ExaminationStage.READING, ExaminationStage.EXTRACTING, ExaminationStage.APPRAISING, ExaminationStage.REVIEWING)
    val waiting = setOf(ExaminationStage.WAITING_NETWORK, ExaminationStage.WAITING_RATE_LIMIT, ExaminationStage.WAITING_CAPACITY, ExaminationStage.BLOCKED_SOURCE)
    const val HEARTBEAT_MS = 15_000L
    const val ATTEMPT_MS = 15 * 60_000L
    fun phaseMillis(stage: ExaminationStage): Long = when (stage) {
        ExaminationStage.QUEUED, ExaminationStage.RESOLVING, ExaminationStage.READING -> 2 * 60_000L
        ExaminationStage.EXTRACTING, ExaminationStage.REVIEWING -> 3 * 60_000L
        ExaminationStage.APPRAISING -> 60_000L
        else -> 0L
    }
}
