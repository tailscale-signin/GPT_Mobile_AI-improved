package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GatewayEngineeringSettings(val durationHours: Int = 3, val repairAttempts: Int = 5) {
    init {
        require(durationHours in setOf(0, 3, 6, 12) && repairAttempts in 1..5)
    }
}

@Serializable
data class GatewayEngineeringRequest(
    val repository: String,
    val branch: String,
    val base: String,
    val objective: String,
    val model: String,
    @SerialName("duration_hours") val durationHours: Int = 3,
    @SerialName("repair_attempts") val repairAttempts: Int = 5,
    @SerialName("chat_id") val chatId: Int,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class GatewayEngineeringUpdate(val revision: Int, val state: String, val message: String, @SerialName("created_at") val createdAt: Double)

@Serializable
data class GatewayEngineeringJob(
    val id: String,
    val repository: String,
    val branch: String,
    @SerialName("chat_id") val chatId: Int = 0,
    val state: String,
    val revision: Int,
    @SerialName("created_at") val createdAt: Double,
    val deadline: Double,
    val message: String,
    @SerialName("milestones_completed") val milestonesCompleted: Int = 0,
    @SerialName("milestones_total") val milestonesTotal: Int = 1,
    @SerialName("repair_attempts") val repairAttempts: Int = 0,
    val pr: Int? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    @SerialName("changed_files") val changedFiles: List<String> = emptyList(),
    val tests: List<JsonObject> = emptyList(),
    @SerialName("checks_passed") val checksPassed: Boolean = false,
    @SerialName("merge_ready") val mergeReady: Boolean = false,
    @SerialName("approval_expires_at") val approvalExpiresAt: Double? = null,
    @SerialName("merge_sha") val mergeSha: String? = null,
    val updates: List<GatewayEngineeringUpdate> = emptyList(),
    @SerialName("next_revision") val nextRevision: Int? = null,
    @SerialName("has_more_updates") val hasMoreUpdates: Boolean = false,
    @SerialName("finished_at") val finishedAt: Double? = null
) {
    val terminal: Boolean get() = state in setOf("COMPLETED", "CANCELLED")
    val progress: Float get() = (milestonesCompleted.toFloat() / milestonesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** No credentials or merge approval are persisted on Android. Pending creates retain their idempotency key. */
@Serializable
data class GatewayEngineeringBinding(
    val profileUid: String,
    val endpoint: String,
    val request: GatewayEngineeringRequest,
    val job: GatewayEngineeringJob? = null,
    val updates: List<GatewayEngineeringUpdate> = emptyList(),
    val syncedRevision: Int = -1,
    val seenRevision: Int = -1,
    val notifiedRevision: Int = -1,
    val principalId: String? = null,
    val localChatId: Int? = null
) {
    /** Remote request identity stays immutable when a backup maps local conversation IDs. */
    val chatId: Int get() = localChatId ?: request.chatId
}
