package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep repository dispatch inside the target for which this task can retain acceptance evidence. */
internal class GitHubBoundRepositoryTool(
    private val delegate: AgentTool,
    repository: GitHubRepositoryContext,
    private val connectionMatches: Boolean = true
) : PreparableAgentTool,
    OwnedAgentTool {
    private val repository = normalizeGitHubRepository(repository.owner, repository.repo).let { (owner, repo) ->
        repository.copy(owner = requireNotNull(owner), repo = requireNotNull(repo))
    }
    override val definition get() = delegate.definition
    override val managesExecutionBudget get() = delegate.managesExecutionBudget
    override val coordinatesChildWork get() = delegate.coordinatesChildWork
    override val executionOwner get() = (delegate as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool =
        GitHubBoundRepositoryTool(
            delegate.withChildBudget { child -> bind(GitHubBoundRepositoryTool(child, repository, connectionMatches)) },
            repository,
            connectionMatches
        )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        val bounded = boundArguments(arguments)
        retainBinding(delegate.execute(callId, bounded), arguments, bounded)
    } catch (rejected: Rejected) {
        rejected.result(callId)
    }

    override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
        val bounded = try {
            boundArguments(arguments)
        } catch (rejected: Rejected) {
            return { rejected.result(callId) }
        }
        val prepared = (delegate as? PreparableAgentTool)?.prepareExecution(callId, bounded)
            ?: { delegate.execute(callId, bounded) }
        return { retainBinding(prepared(), arguments, bounded) }
    }

    /** Verification must see the exact normalized target supplied to the executor. */
    private fun retainBinding(result: AgentToolResult, original: JsonObject, bounded: JsonObject): AgentToolResult {
        if (bounded == original || result.executionReceipt?.get("corrected_arguments") is JsonObject) return result
        return result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("corrected_arguments" to bounded)))
    }

    private fun boundArguments(arguments: JsonObject): JsonObject {
        // Saved views are authorized local evidence, with their own exact cursor contract.
        // Graph operations already carry an immutable workspace scope and reject target overrides.
        if (GitHubOperationTool.operationName(definition.name) != null && "view_cursor" in arguments) return arguments
        val native = if (definition.name.substringBefore("__") == "github") {
            arguments
        } else {
            GitHubOperationTool.nativeArguments(definition.name, arguments) ?: return arguments
        }
        val action = (native["action"] as? JsonPrimitive)?.content.orEmpty()
        if (GitHubTargetPolicy.isAccountAction(action) || action == "read_saved_result") return arguments
        val requested = try {
            normalizeGitHubRepository(stringField(arguments, "owner"), stringField(arguments, "repo"))
        } catch (invalid: IllegalArgumentException) {
            throw Rejected("INVALID_ARGUMENTS", invalid.message.orEmpty(), null)
        }
        val owner = requested.first ?: repository.owner
        val repo = requested.second ?: repository.repo
        if (!connectionMatches || !owner.equals(repository.owner, true) || !repo.equals(repository.repo, true)) {
            throw Rejected(
                "GITHUB_REPOSITORY_TARGET_MISMATCH",
                "This task's evidence is bound to ${repository.fullName} and its selected connection. Select the intended repository and connection before starting dependent work. No repository operation was dispatched.",
                "$owner/$repo"
            )
        }
        // Explicitly pin both fields so a restored or discovered context cannot silently retarget omissions.
        // Branches, refs, paths and operation aliases retain their original meaning.
        return JsonObject(arguments + mapOf("owner" to JsonPrimitive(repository.owner), "repo" to JsonPrimitive(repository.repo)))
    }

    private fun stringField(arguments: JsonObject, key: String): String? {
        val value = arguments[key] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "$key must be a repository name string." }
        return value.content
    }

    private inner class Rejected(val code: String, message: String, val requested: String?) : IllegalArgumentException(message) {
        fun result(callId: String) = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.bound-target.v1")
                    put("error_code", code)
                    put("dispatched", false)
                    put("selected_repository", repository.fullName)
                    requested?.let { put("requested_repository", it) }
                    put("message", message.orEmpty())
                }
            ),
            isError = true,
            errorCode = code,
            dispatched = false
        )
    }
}
