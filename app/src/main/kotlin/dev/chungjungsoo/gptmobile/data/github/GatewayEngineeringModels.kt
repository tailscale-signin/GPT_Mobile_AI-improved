package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GatewayEngineeringCapabilities(
    val service: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val enabled: Boolean = false,
    @SerialName("principal_id") val principalId: String? = null,
    @SerialName("gateway_version") val gatewayVersion: String? = null,
    @SerialName("server_build") val serverBuild: String? = null,
    @SerialName("code_graph") val codeGraph: GatewayGraphCapability? = null,
    val readiness: GatewayEngineeringReadiness? = null
) {
    fun unavailableReason(graph: Boolean = false): String? {
        val ready = if (graph) codeGraph?.enabled == true && readiness?.graphReady != false else enabled && readiness?.ready != false
        if (ready) return null
        val checks = readiness?.checks
        return when {
            checks?.journal == "disabled" -> "Enable durable storage in the selected desktop gateway before using engineering jobs or repository graphs."
            checks?.journal in setOf("unavailable", "write_failed") -> "The engineering journal is unavailable. Check the desktop gateway storage and retained jobs before retrying."
            checks?.hostGithubConnection == "missing" -> "Configure the GitHub connection on the selected gateway host. Chat inference availability does not establish engineering or repository graph readiness."
            checks?.draining == true -> "The selected engineering gateway is stopping or updating. Reconnect to its retained jobs when it is ready."
            !graph && checks?.worker == "unavailable" -> "The engineering worker is not running. Check the selected desktop gateway; retained jobs remain available."
            graph && codeGraph?.enabled != true -> "The selected gateway has not advertised an available repository graph service. Check its engineering status and update it if needed."
            else -> "The gateway engineering service is disabled or unavailable. Chat inference readiness does not establish job readiness."
        }
    }
}

@Serializable
data class GatewayGraphCapability(@SerialName("schema_version") val schemaVersion: Int = 0, val enabled: Boolean = false)

@Serializable
data class GatewayEngineeringReadiness(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val ready: Boolean = false,
    @SerialName("graph_ready") val graphReady: Boolean = false,
    @SerialName("saved_results") val savedResults: Boolean = false,
    val checks: GatewayEngineeringChecks = GatewayEngineeringChecks()
)

@Serializable
data class GatewayEngineeringChecks(
    val journal: String? = null,
    @SerialName("host_github_connection") val hostGithubConnection: String? = null,
    val worker: String? = null,
    val draining: Boolean = false
)

@Serializable
data class GatewayEngineeringSettings(val durationHours: Int = 3, val repairAttempts: Int = 5, val acceptance: List<GatewayEngineeringAcceptance> = emptyList()) {
    init {
        require(durationHours in setOf(0, 3, 6, 12) && repairAttempts in 1..5)
        require(acceptance.size <= 32 && acceptance.map { it.id }.distinct().size == acceptance.size)
    }
}

@Serializable
data class GatewayEngineeringAcceptance(val id: String, val paths: List<String>, val checks: List<String>, val evidence: List<GatewayRequirementEvidence> = emptyList()) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(paths.size in 1..32 && checks.size in 1..32)
        require((paths + checks).all { it.isNotBlank() && it.length <= 400 })
        require(paths.all { !it.startsWith('/') && '\\' !in it && it.split('/').none { part -> part in setOf("", ".", "..", ".git") } })
        require(evidence.size <= 32)
    }
}

@Serializable
data class GatewayRequirementEvidence(val kind: String, val name: String) {
    init {
        require(kind in setOf("behavior_test", "api_receipt", "device_trace", "model_runtime", "artifact") && name.length in 1..200)
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
    @SerialName("request_id") val requestId: String,
    val acceptance: List<GatewayEngineeringAcceptance> = emptyList(),
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("memory_mode") val memoryMode: GitHubEngineeringMemoryMode = GitHubEngineeringMemoryMode.HYBRID,
    @SerialName("execution_binding_id") val executionBindingId: String? = null,
    val scope: String = "implement",
    @SerialName("profile_name") val profileName: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null
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
    @SerialName("finished_at") val finishedAt: Double? = null,
    @SerialName("completion_verification") val completionVerification: JsonObject? = null,
    @SerialName("latest_evidence") val latestEvidence: JsonObject? = null,
    @SerialName("execution_binding_id") val executionBindingId: String? = null,
    @SerialName("execution_binding") val executionBinding: JsonObject? = null,
    @SerialName("latest_execution_receipt") val latestExecutionReceipt: JsonObject? = null,
    @SerialName("blocked_reason") val blockedReason: JsonObject? = null,
    @SerialName("final_report") val finalReport: JsonObject? = null,
    @SerialName("gateway_id") val gatewayId: String? = null,
    @SerialName("principal_id") val principalId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("accepted_at_ms") val acceptedAtMs: Long? = null,
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("scheduling_state") val schedulingState: String? = null,
    @SerialName("queue_position") val queuePosition: Int? = null,
    @SerialName("permitted_actions") val permittedActions: List<String> = emptyList(),
    @SerialName("conversation_revision") val conversationRevision: Long? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null,
    val base: String? = null
) {
    val terminal: Boolean get() = schedulingState in setOf("completed", "failed", "cancelled", "interrupted") || state in setOf("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED")
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
    val localChatId: Int? = null,
    val executionSelection: GatewayExecutionSelection? = null,
    val executionAdmission: GatewayExecutionAdmission? = null,
    val gatewayId: String? = null,
    val identityScope: String? = null,
    val conversationId: String? = null,
    val originalContext: GitHubConversationContext? = null,
    val userMessageId: Int? = null,
    val assistantMessageId: Int? = null,
    val localRunId: String? = null
) {
    /** Remote request identity stays immutable when a backup maps local conversation IDs. */
    val chatId: Int get() = localChatId ?: request.chatId
}
