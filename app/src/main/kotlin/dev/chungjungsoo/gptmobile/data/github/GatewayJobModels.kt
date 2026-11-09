package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GatewayJobCatalog(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("server_now") val serverNow: Double,
    val items: List<GatewayJobSummary> = emptyList(),
    val counts: GatewayJobCounts? = null,
    val complete: Boolean = false,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null
)

@Serializable
data class GatewayJobCounts(
    val running: Int = 0,
    val queued: Int = 0,
    @SerialName("needs_attention") val needsAttention: Int = 0,
    val recent: Int = 0
) {
    val active: Int get() = running + queued + needsAttention
}

@Serializable
data class GatewayJobSummary(
    @SerialName("job_id") val jobId: String,
    @SerialName("job_kind") val jobKind: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    val title: String = "",
    @SerialName("profile_id") val profileId: String? = null,
    val model: String = "",
    val repository: String? = null,
    val branch: String? = null,
    val base: String? = null,
    val state: String,
    @SerialName("scheduling_state") val schedulingState: String? = null,
    val phase: String? = null,
    val revision: Long = 0,
    @SerialName("accepted_at") val acceptedAt: Double,
    @SerialName("queued_at") val queuedAt: Double? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("finished_at") val finishedAt: Double? = null,
    @SerialName("server_now") val serverNow: Double? = null,
    @SerialName("queue_reason") val queueReason: String? = null,
    @SerialName("queue_position") val queuePosition: Int? = null,
    @SerialName("result_available") val resultAvailable: Boolean = false,
    @SerialName("allowed_actions") val allowedActions: List<String> = emptyList(),
    @SerialName("cancellation_requested") val cancellationRequested: Boolean = false,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null
) {
    val group: GatewayJobGroup get() = gatewayJobGroup(schedulingState ?: state)
    val acceptsInstruction: Boolean get() = jobKind == "engineering" && conversationId != null && !cancellationRequested && group in setOf(GatewayJobGroup.RUNNING, GatewayJobGroup.QUEUED) && state.uppercase() != "CANCELLING" && phase?.uppercase() != "CANCELLING"
    val displayTitle: String get() = title.trim().take(120).ifBlank { repository ?: model.ifBlank { "Gateway job" } }
}

enum class GatewayJobGroup { RUNNING, QUEUED, NEEDS_ATTENTION, RECENT }

internal fun gatewayJobGroup(state: String): GatewayJobGroup = when (state.uppercase()) {
    "QUEUED", "PENDING", "ACCEPTED", "WAITING_CAPACITY" -> GatewayJobGroup.QUEUED
    "CANCELLING", "NEEDS_ATTENTION", "NEEDS_INPUT", "MERGE_READY", "AWAITING_APPROVAL", "WAITING_APPROVAL", "WAITING_DEVICE", "WAITING_FOR_DEVICE", "BLOCKED", "RECOVERY_BLOCKED", "RECONCILING", "INTERRUPTED", "UNKNOWN_OUTCOME" -> GatewayJobGroup.NEEDS_ATTENTION
    "COMPLETED", "SUCCEEDED", "FINISHED", "DONE", "FAILED", "ERROR", "CANCELED", "CANCELLED", "EXPIRED" -> GatewayJobGroup.RECENT
    "RUNNING", "STARTING", "STREAMING", "GENERATING", "PREFILL", "THINKING", "TOOL_EXECUTION", "FINALIZING", "PLAN", "READ", "IMPLEMENT", "REVIEW", "COMMIT", "OPEN_PR", "CI", "WAIT_CI", "PROMOTE", "MERGE", "VERIFY" -> GatewayJobGroup.RUNNING
    else -> GatewayJobGroup.NEEDS_ATTENTION
}

/** Local wall-clock changes must not speed up a live timer; callers supply monotonic elapsed time. */
internal fun gatewayElapsedMillis(acceptedAt: Double, finishedAt: Double?, observedServerNow: Double, elapsedSinceObservation: Long): Long {
    if (!acceptedAt.isFinite() || !observedServerNow.isFinite()) return 0
    val end = finishedAt?.takeIf(Double::isFinite) ?: (observedServerNow + elapsedSinceObservation.coerceAtLeast(0) / 1000.0)
    return ((end - acceptedAt) * 1000.0).coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
}

internal fun gatewayIdentityKey(vararg parts: String): String {
    val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun requireGatewayIdentity(gatewayId: String, principalId: String) {
    require(listOf(gatewayId, principalId).all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }) { "The gateway did not return a valid saved-work identity." }
}

@Serializable
data class GatewayConversationSnapshot(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("conversation_id") val conversationId: String,
    val title: String = "Gateway conversation",
    @SerialName("profile_id") val profileId: String? = null,
    val model: String = "",
    val repository: String? = null,
    val branch: String? = null,
    val base: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    val revision: Long = 0,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
    @SerialName("transcript_available") val transcriptAvailable: Boolean = true,
    @SerialName("active_job_id") val activeJobId: String? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null,
    @SerialName("history_complete") val historyComplete: Boolean = true,
    @SerialName("history_limitation") val historyLimitation: String? = null,
    @SerialName("execution_binding") val executionBinding: JsonObject? = null
)

@Serializable
data class GatewayConversationPage(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("server_now") val serverNow: Double = 0.0,
    val items: List<GatewayConversationSnapshot> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null
)

@Serializable
data class GatewayTranscriptPage(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("conversation_id") val conversationId: String,
    val revision: Long = 0,
    val messages: List<GatewayTranscriptMessage> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("history_complete") val historyComplete: Boolean = true,
    @SerialName("history_limitation") val historyLimitation: String? = null,
    val gap: Boolean = false
)

@Serializable
data class GatewayTranscriptMessage(
    @SerialName("message_id") val messageId: String,
    @SerialName("turn_id") val turnId: String,
    val role: String,
    val content: String = "",
    val status: String = "complete",
    val revision: Long = 0,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("publication_id") val publicationId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("logical_message_id") val logicalMessageId: String = messageId,
    @SerialName("part_index") val partIndex: Int = 0,
    @SerialName("part_count") val partCount: Int = 1,
    @SerialName("content_sha256") val contentSha256: String? = null,
    val kind: String = "message"
)

data class GatewayTranscriptPart(val messageId: String, val logicalMessageId: String, val role: String, val kind: String, val content: String, val partIndex: Int, val partCount: Int, val contentVerified: Boolean)

data class GatewayLocalTranscriptPage(val conversationKey: String, val title: String, val profileUid: String?, val historyComplete: Boolean, val historyLimitation: String?, val items: List<GatewayTranscriptPart>, val nextCursor: String?)

data class GatewayConversationAttachment(
    val chatId: Int,
    val profileUid: String,
    val context: GitHubConversationContext?,
    val conversationKey: String,
    val historyComplete: Boolean = true,
    val conversationMode: String? = null
)

class GatewayHistoryUnsupported internal constructor(
    surface: GatewayAdmissionSurface = GatewayAdmissionSurface.CONVERSATIONS,
    httpStatus: Int? = null,
    val directModelServer: Boolean = false
) : GatewayAdmissionException(GatewayAdmissionReason.UNSUPPORTED_CONTRACT, httpStatus, null, surface) {
    override val message: String? get() = if (directModelServer) {
        "GATEWAY_UNSUPPORTED_CONTRACT (HTTP $httpStatus): The selected address identifies itself as llama.cpp and does not expose gateway Jobs. Select the configured desktop gateway address, or repair the reverse-proxy gateway routes. Direct model availability does not verify saved-work support."
    } else {
        super.message
    }
}

@Serializable
data class GatewayCommandReceipt(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("job_kind") val jobKind: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("profile_id") val profileId: String? = null,
    val model: String? = null,
    val repository: String? = null,
    val branch: String? = null,
    val base: String? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null
)

@Serializable
internal data class GatewayScopeIdentity(
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("instance_id") val instanceId: String? = null
)

@Serializable
internal data class GatewayOrdinaryReservation(val reservationKind: String, val model: String, val gatewayId: String, val principalId: String)

internal class GatewayCursorExpired(surface: GatewayAdmissionSurface = GatewayAdmissionSurface.TRANSCRIPT, httpStatus: Int? = null, wireCode: String? = null) : GatewayAdmissionException(GatewayAdmissionReason.CURSOR_EXPIRED, httpStatus, wireCode, surface)

internal class GatewayTranscriptTooLarge : IllegalStateException("The saved transcript is too large to load on this device (65 MiB page limit). Its server copy is retained.")
