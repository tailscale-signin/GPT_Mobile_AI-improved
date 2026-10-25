package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import kotlinx.serialization.Serializable

/** Immutable, bounded UTF-8 payload pages. A staged page is invisible until its manifest commits. */
@Serializable
@Entity(
    tableName = "combined_durable_blocks",
    primaryKeys = ["runId", "payloadId", "sequence"],
    foreignKeys = [ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)]
)
data class CombinedDurableBlock(
    val runId: String,
    val payloadId: String,
    val sequence: Int,
    val start: Int,
    val text: String,
    val digest: String,
    val previousDigest: String?,
    val utf8Bytes: Int
)
