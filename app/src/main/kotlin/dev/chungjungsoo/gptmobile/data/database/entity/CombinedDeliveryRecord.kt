package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import kotlinx.serialization.Serializable

/** A validated logical delivery record and the exact text/attribution it first produced. */
@Serializable
@Entity(
    tableName = "combined_delivery_records",
    primaryKeys = ["runId", "revisionId", "fence", "chainDigest"],
    foreignKeys = [ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)]
)
data class CombinedDeliveryRecord(
    val runId: String,
    val revisionId: String,
    val fence: Long,
    val sequence: Int,
    val recordId: String,
    val wirePayload: String,
    val wireDigest: String,
    val answerPayload: String,
    val attributionPayload: String,
    val renderedStart: Int,
    val renderedEnd: Int,
    val previousChainDigest: String,
    val chainDigest: String
)
