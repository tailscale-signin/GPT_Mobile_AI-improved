package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A cached projection. Credentials stay in the selected connection, never in a job. */
@Entity(tableName = "gateway_jobs", indices = [Index(value = ["connectionProfileUid", "endpoint"]), Index(value = ["conversationKey"])])
data class GatewayJobRecord(
    @PrimaryKey val key: String,
    val gatewayId: String,
    val principalId: String,
    val kind: String,
    val remoteJobId: String,
    val conversationKey: String?,
    val connectionProfileUid: String,
    val endpoint: String,
    val executionProfileUid: String?,
    val snapshot: String,
    val observedAtMillis: Long,
    val seenRevision: Long = -1
)
