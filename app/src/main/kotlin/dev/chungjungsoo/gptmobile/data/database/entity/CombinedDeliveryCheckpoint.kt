package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/** Record journal and rendered UTF-16 spans advance in one Room transaction, independently of UI visibility. */
@Entity(
    tableName = "combined_delivery_checkpoints",
    primaryKeys = ["runId", "revisionId"],
    foreignKeys = [ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)]
)
@kotlinx.serialization.Serializable
data class CombinedDeliveryCheckpoint(
    val runId: String,
    val revisionId: String,
    val fence: Long,
    val generation: Long,
    val snapshotDigest: String,
    val snapshotJson: String,
    val stateJson: String,
    val renderedAnswer: String,
    val attributionJson: String,
    val updatedAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "1")
    val storageVersion: Int = 1,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val recordCount: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "''")
    val recordsDigest: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''")
    val payloadsJson: String = ""
)
