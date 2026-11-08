package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GatewayIdentity(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("identity_scope") val identityScope: String,
    @SerialName("server_time_ms") val serverTimeMs: Long
) {
    fun validate() {
        require(protocolVersion == 1 && serverTimeMs > 0) { "Unsupported gateway continuity protocol." }
        require(listOf(gatewayId, instanceId, principalId, identityScope).all { it.length in 1..256 && it.none(Char::isISOControl) }) { "Invalid gateway identity." }
    }
}

@Serializable
data class GatewayCatalogJob(
    @SerialName("job_id") val jobId: String,
    val kind: String,
    @SerialName("conversation_id") val conversationId: String,
    val revision: Long,
    val phase: String,
    @SerialName("scheduling_state") val schedulingState: String,
    val title: String = "Gateway job",
    val scope: String = "read",
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("profile_name") val profileName: String = "",
    val model: String = "",
    val repository: String? = null,
    val branch: String? = null,
    @SerialName("accepted_at_ms") val acceptedAtMs: Long? = null,
    @SerialName("started_at_ms") val startedAtMs: Long? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    @SerialName("finished_at_ms") val finishedAtMs: Long? = null,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("queue_position") val queuePosition: Int? = null,
    @SerialName("permitted_actions") val permittedActions: List<String> = emptyList(),
    @SerialName("execution_binding") val executionBinding: JsonObject? = null,
    @SerialName("latest_message_sequence") val latestMessageSequence: Long = 0,
    @SerialName("history_complete") val historyComplete: Boolean = false,
    @SerialName("history_limitation") val historyLimitation: String? = null,
    @SerialName("conversation_revision") val conversationRevision: Long? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null,
    val base: String? = null
) {
    val terminal: Boolean get() = schedulingState in setOf("completed", "failed", "cancelled", "interrupted")
}

@Serializable
data class GatewayJobCatalog(
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    val items: List<GatewayCatalogJob>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
data class GatewayConversationSnapshot(
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("conversation_id") val conversationId: String,
    val revision: Long,
    val title: String,
    @SerialName("execution_binding") val executionBinding: JsonObject? = null,
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_name") val profileName: String = "",
    val model: String,
    val repository: String? = null,
    val branch: String? = null,
    @SerialName("latest_job_id") val latestJobId: String? = null,
    @SerialName("message_watermark") val messageWatermark: Long = 0,
    @SerialName("replay_cursor") val replayCursor: String? = null,
    @SerialName("history_complete") val historyComplete: Boolean = false,
    @SerialName("history_limitation") val historyLimitation: String? = null,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("permitted_actions") val permittedActions: List<String> = emptyList(),
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null,
    @SerialName("latest_job") val latestJob: GatewayCatalogJob? = null,
    val base: String? = null
)

@Serializable
data class GatewayConversationMessage(
    @SerialName("message_id") val messageId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("job_id") val jobId: String,
    val sequence: Long,
    val role: String,
    val kind: String,
    val content: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("logical_message_id") val logicalMessageId: String = messageId,
    @SerialName("part_index") val partIndex: Int = 0,
    @SerialName("part_count") val partCount: Int = 1,
    @SerialName("content_sha256") val contentSha256: String? = null
)

@Serializable
data class GatewayMessagePage(
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("conversation_id") val conversationId: String,
    val revision: Long,
    val items: List<GatewayConversationMessage>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("earliest_sequence") val earliestSequence: Long = 0,
    @SerialName("latest_sequence") val latestSequence: Long = 0,
    @SerialName("history_complete") val historyComplete: Boolean = false,
    @SerialName("history_limitation") val historyLimitation: String? = null,
    val gap: Boolean = false,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("gap_details") val gapDetails: GatewayReplayGap? = null
)

@Serializable
data class GatewayReplayGap(@SerialName("missing_ranges") val missingRanges: List<GatewayMissingRange>)

@Serializable
data class GatewayMissingRange(@SerialName("first_sequence") val firstSequence: Long, @SerialName("last_sequence") val lastSequence: Long)

@Serializable
data class GatewayCommandReceipt(
    @SerialName("command_id") val commandId: String,
    val status: String,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("job_id") val jobId: String,
    val revision: Long,
    @SerialName("accepted_at_ms") val acceptedAtMs: Long,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    val job: GatewayEngineeringJob
)

data class GatewayConversationObservation(
    val currentJob: GatewayCatalogJob? = null,
    val submissionState: String? = null,
    val connectionState: String = "UNVERIFIED",
    val stale: Boolean = true,
    val requestId: String? = null,
    val conversationId: String? = null,
    val error: String? = null
)

data class GatewayStopOutcome(val state: String, val message: String)
