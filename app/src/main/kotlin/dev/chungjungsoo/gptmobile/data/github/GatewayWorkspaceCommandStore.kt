package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Arguments live in the existing encrypted workspace store, never in binding metadata. */
@Singleton
class GatewayWorkspaceCommandStore @Inject constructor(private val workspace: WorkspaceRepository) {
    suspend fun retain(binding: GatewayEngineeringBinding, command: GatewayWorkspacePendingCommand, arguments: JsonObject): String {
        requireWorkspaceArguments(arguments)
        val reference = "gateway-workspace-command:" + gatewayIdentityKey(binding.gatewayId.orEmpty(), binding.principalId.orEmpty(), command.jobId, command.commandId)
        val payload = payload(binding, command, arguments)
        check(workspace.saveProtectedSynthesisCheckpoint(reference, binding.chatId, binding.localRunId ?: command.jobId, payload, appendOnly = true)) { "The workspace command could not be saved securely. Nothing was dispatched." }
        return reference
    }

    suspend fun recover(binding: GatewayEngineeringBinding, command: GatewayWorkspacePendingCommand): JsonObject {
        val reference = requireNotNull(command.argumentsReference) { "The original command arguments are unavailable. The saved command was not replayed." }
        val retained = requireNotNull(workspace.protectedSynthesisCheckpoint(reference)) { "The original command arguments are unavailable. The saved command was not replayed." }
        val arguments = retained["arguments"] as? JsonObject ?: error("The saved workspace command is malformed.")
        check(retained == payload(binding, command, arguments)) { "The saved command belongs to a different job, source or connection." }
        requireWorkspaceArguments(arguments)
        return arguments
    }

    private fun payload(binding: GatewayEngineeringBinding, command: GatewayWorkspacePendingCommand, arguments: JsonObject) = buildJsonObject {
        put("schema_version", 1)
        put("gateway_id", binding.gatewayId)
        put("principal_id", binding.principalId)
        put("endpoint", binding.endpoint)
        put("profile_uid", binding.profileUid)
        put("request_id", binding.request.requestId)
        put("job_id", command.jobId)
        put("command_id", command.commandId)
        put("head_sha", command.headSha)
        put("revision", command.revision)
        put("action", command.action)
        put("arguments", arguments)
    }
}
