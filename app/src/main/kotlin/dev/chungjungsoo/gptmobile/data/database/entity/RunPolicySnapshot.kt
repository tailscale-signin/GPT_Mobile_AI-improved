package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Immutable accepted policy fragments. The digest covers the original UTF-8 bytes. */
@Entity(
    tableName = "run_policy_snapshots",
    primaryKeys = ["chatId", "digest"],
    foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)]
)
data class RunPolicySnapshot(
    val chatId: Int,
    val digest: String,
    val schemaVersion: Int,
    val kind: String,
    val policyRevision: String,
    val taskDigest: String,
    val authorityDigest: String,
    val payload: String
)

/** All transitive fragments are linked to the run, so deletion cannot retain orphan prompts. */
@Entity(
    tableName = "agent_run_policy_refs",
    primaryKeys = ["runId", "digest"],
    foreignKeys = [
        ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RunPolicySnapshot::class, parentColumns = ["chatId", "digest"], childColumns = ["chatId", "digest"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["chatId", "digest"])]
)
data class AgentRunPolicyRef(val runId: String, val chatId: Int, val digest: String)
