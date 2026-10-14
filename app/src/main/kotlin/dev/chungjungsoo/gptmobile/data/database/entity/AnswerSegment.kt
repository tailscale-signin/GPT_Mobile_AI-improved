package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/** Immutable committed prefix chunks; source and output ownership remain run-scoped. */
@Entity(
    tableName = "answer_segments",
    primaryKeys = ["runId", "sequence"],
    foreignKeys = [ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)]
)
@kotlinx.serialization.Serializable
data class AnswerSegment(
    val runId: String,
    val sequence: Int,
    val fence: Long,
    val text: String,
    val digest: String,
    val previousDigest: String?,
    val utf8Bytes: Int
)
