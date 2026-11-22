package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A readback of one existing job. No field grants a different job or source authority. */
@Serializable
data class GatewayAgentWorkspace(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("job_id") val jobId: String = "",
    @SerialName("principal_id") val principalId: String = "",
    @SerialName("gateway_id") val gatewayId: String = "",
    val repository: String = "",
    val branch: String = "",
    @SerialName("head_sha") val headSha: String = "",
    val revision: Int = -1,
    val state: String = "",
    val workers: List<JsonObject> = emptyList(),
    val terminals: List<JsonObject> = emptyList(),
    val requirements: List<JsonObject> = emptyList(),
    val environment: JsonObject = JsonObject(emptyMap()),
    val agents: List<JsonObject> = emptyList(),
    @SerialName("product_references") val productReferences: List<JsonObject> = emptyList(),
    @SerialName("agent_runs") val agentRuns: List<JsonObject> = emptyList(),
    val instructions: List<JsonElement> = emptyList(),
    @SerialName("tool_profile") val toolProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("task_plan") val taskPlan: JsonObject = JsonObject(emptyMap()),
    val integrations: JsonObject = JsonObject(emptyMap()),
    @SerialName("next_edit_readiness") val nextEditReadiness: JsonObject = JsonObject(emptyMap()),
    @SerialName("next_edits") val nextEdits: List<JsonObject> = emptyList(),
    @SerialName("context_pack") val contextPack: JsonObject? = null,
    val summaries: List<JsonObject> = emptyList(),
    val commands: List<JsonObject> = emptyList(),
    @SerialName("review_drafts") val reviewDrafts: List<JsonObject> = emptyList(),
    @SerialName("candidate_comparison") val candidateComparison: JsonObject = JsonObject(emptyMap()),
    @SerialName("available_actions") val availableActions: List<String> = emptyList()
) {
    fun requireBinding(binding: GatewayEngineeringBinding) {
        val job = requireNotNull(binding.job) { "Reconnect to the saved job before opening its workspace." }
        check(schemaVersion == 1 && jobId == job.id && principalId.isNotBlank() && gatewayId.isNotBlank()) { "The Gateway returned an unsupported workspace identity." }
        check(principalId == binding.principalId && gatewayId == binding.gatewayId && repository == binding.request.repository && branch == job.branch) { "This workspace belongs to a different Gateway, account or repository." }
        check(headSha.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}")) && headSha == job.headSha && revision >= job.revision) { "The saved source changed. Check the saved job before reopening its workspace." }
        check(workers.size <= 8 && terminals.size <= 3 && agents.size <= 32 && agentRuns.size <= 32 && requirements.size <= 256 && commands.size <= 128 && summaries.size <= 32) { "The workspace exceeds its supported bounded view." }
    }

    fun canRun(action: String): Boolean = action in availableActions && action in GatewayWorkspaceAction.names

    fun canRunWithUnresolvedCommands(binding: GatewayEngineeringBinding, action: String): Boolean =
        canRun(action) && (binding.workspaceUnresolvedCommands.none { !it.settled } || state == "NEEDS_INPUT" && action in GatewayWorkspaceAction.maintenance)
}

internal object GatewayWorkspaceAction {
    val names = setOf("worker_wave", "worker_status", "task_plan", "task_plan_status", "task_plan_complete", "context_pack", "context_summary", "tool_profile", "integration_status", "integration_call", "next_edit", "next_edit_validate", "agent_start", "agent_status", "agent_events", "agent_artifacts", "agent_steer", "agent_cancel", "agent_import", "agent_qualify", "agent_compare", "agent_approve_plan", "review_draft", "environment_qualify", "candidate_rank")
    val maintenance = setOf("environment_qualify", "integration_status", "agent_qualify", "agent_status", "agent_events", "agent_artifacts", "agent_cancel", "review_draft")
}

/** Saved before dispatch; only an exact command lookup can settle an uncertain write. */
@Serializable
data class GatewayWorkspacePendingCommand(
    @SerialName("command_id") val commandId: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("head_sha") val headSha: String,
    val revision: Int,
    val action: String,
    val receipt: GatewayWorkspaceCommandReceipt? = null,
    val argumentsReference: String? = null,
    val dismissedAbsent: Boolean = false
) {
    val settled: Boolean get() = receipt?.settled == true || dismissedAbsent && receipt?.status == "NOT_FOUND" && receipt.confirmedAbsent
}

@Serializable
data class GatewayWorkspaceCommandReceipt(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("job_id") val jobId: String = "",
    @SerialName("command_id") val commandId: String = "",
    @SerialName("head_sha") val headSha: String = "",
    val status: String = "UNKNOWN",
    @SerialName("confirmed_absent") val confirmedAbsent: Boolean = false,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
    val reason: String? = null,
    @SerialName("result_reference") val resultReference: JsonObject? = null
) {
    val settled: Boolean get() = status in setOf("COMPLETED", "FAILED", "REJECTED", "CANCELLED")

    /** Results may contain repository content. The server owns encrypted evidence retention. */
    fun metadataOnly(): GatewayWorkspaceCommandReceipt = copy(result = null, error = null, reason = reason?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,100}")) })

    fun requireCommand(command: GatewayWorkspacePendingCommand) {
        check(schemaVersion == 1 && jobId == command.jobId && commandId == command.commandId && (headSha == command.headSha || status == "NOT_FOUND" && confirmedAbsent)) { "The workspace command receipt belongs to a different operation." }
        check(status in setOf("QUEUED", "RUNNING", "COMPLETED", "FAILED", "REJECTED", "CANCELLED", "UNKNOWN") || status == "NOT_FOUND" && confirmedAbsent) { "The workspace command outcome is not recognized." }
    }
}

internal fun requireWorkspaceArguments(arguments: JsonObject) {
    require(arguments.keys.none { it in setOf("head_sha", "action", "revision", "command_id", "request_id", "job_id", "principal_id", "gateway_id") }) { "Workspace arguments cannot override the saved operation identity." }
    require(arguments.toString().toByteArray().size <= 64_000) { "The workspace command is too large." }
}

/** Source browsing is always tied to an observed immutable Git object. */
@Serializable
data class GatewayWorkspaceSourcePath(val path: String, @SerialName("blob_sha") val blobSha: String)

@Serializable
data class GatewayWorkspaceTree(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("job_id") val jobId: String,
    @SerialName("head_sha") val headSha: String,
    val paths: List<GatewayWorkspaceSourcePath>,
    val total: Int,
    val offset: Int,
    @SerialName("next_offset") val nextOffset: Int? = null
) {
    fun requirePage(job: String, head: String, requestedOffset: Int) {
        check(schemaVersion == 1 && jobId == job && headSha == head && offset == requestedOffset) { "The source tree belongs to another saved source." }
        check(paths.size <= 100 && total >= 0 && offset >= 0 && offset <= total && paths.size <= total - offset) { "The source tree page is invalid." }
        check(nextOffset == null || nextOffset == offset + paths.size && nextOffset > offset && nextOffset < total) { "The source tree cursor is invalid." }
        check(paths.map { it.path }.distinct().size == paths.size && paths.all { it.path.isWorkspacePath() && it.blobSha.isWorkspaceSha() }) { "The source tree contains an invalid file identity." }
    }
}

@Serializable
data class GatewayWorkspaceSource(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("job_id") val jobId: String,
    @SerialName("head_sha") val headSha: String,
    val path: String,
    @SerialName("blob_sha") val blobSha: String,
    val content: String,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("total_lines") val totalLines: Int,
    @SerialName("next_line") val nextLine: Int? = null
) {
    fun requirePage(job: String, head: String, file: GatewayWorkspaceSourcePath, requestedLine: Int) {
        check(schemaVersion == 1 && jobId == job && headSha == head && path == file.path && blobSha == file.blobSha && startLine == requestedLine) { "The source file belongs to another saved source." }
        check(totalLines >= 0 && startLine > 0 && endLine in (startLine - 1)..totalLines && endLine - startLine < 80 && content.toByteArray().size <= 262_144) { "The source page exceeds its supported range." }
        check(nextLine == null || nextLine == endLine + 1 && nextLine > startLine && nextLine <= totalLines) { "The source page cursor is invalid." }
    }
}

internal fun String.isWorkspaceSha(): Boolean = matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))

internal fun String.isWorkspacePath(): Boolean = isNotBlank() && length <= 4096 && !startsWith('/') && !contains('\\') && none { it.code < 32 } && split('/').none { it in setOf("", ".", "..") }

/** Presentation reads are deliberately typed: a malformed collection is never a label. */
fun JsonObject.workspaceText(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

fun JsonObject.workspaceFlag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

fun JsonObject.workspaceCount(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

fun JsonObject.workspaceStrings(name: String): List<String> = (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
