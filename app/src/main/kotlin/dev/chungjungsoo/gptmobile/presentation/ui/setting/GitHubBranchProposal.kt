package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class GitHubBranchProposal(val eventId: String, val branch: String)

/** Only successful structured tool receipts can propose a destination, never AI prose. */
private fun ToolEvent.branchReceipt(context: GitHubConversationContext): GitHubBranchProposal? = runCatching {
    if (status != ToolEventStatus.COMPLETED || isError || connectionUidSnapshot != context.connectionUid) return null
    val args = Json.parseToJsonElement(arguments) as? JsonObject ?: return null
    val native = if (toolName == "github") args else GitHubOperationTool.nativeArguments(modelToolName, args) ?: GitHubOperationTool.nativeArguments(toolName, args) ?: return null
    val action = native.text("action")
    if (action !in setOf("create_branch", "resolve_context", "task_context")) return null
    var receipt = Json.parseToJsonElement(recoveryResult().orEmpty()) as? JsonObject ?: return null
    if (receipt.text("schema") == "github.operation.v1" || receipt.text("schema") == "gptmobile.tool-result.v1") receipt = receipt["data"] as? JsonObject ?: return null
    if (action == "create_branch") receipt = receipt["context"] as? JsonObject ?: return null
    val owner = receipt.text("owner")
    val repo = receipt.text("repo")
    val branch = receipt.text("ref")
    val sha = receipt.text("commit_sha")
    if (!owner.equals(context.owner, true) || !repo.equals(context.repo, true) || branch.isBlank() || !sha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))) return null
    GitHubBranchProposal(eventId, branch)
}.getOrNull()

internal fun ToolEvent.branchProposal(context: GitHubConversationContext): GitHubBranchProposal? = branchReceipt(context)?.takeIf { it.branch != context.ref }

internal fun latestBranchProposal(events: List<ToolEvent>, context: GitHubConversationContext, acknowledged: List<String>): GitHubBranchProposal? =
    events.asReversed().firstNotNullOfOrNull { it.branchReceipt(context) }?.takeIf { it.branch != context.ref && it.eventId !in acknowledged }
