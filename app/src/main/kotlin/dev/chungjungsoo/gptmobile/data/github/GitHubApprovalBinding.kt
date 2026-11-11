package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal data class GitHubNativeDecisionReceipt(
    val taskId: String,
    val chatId: Int,
    val sourceMessageId: Int,
    val branch: String,
    val summary: String,
    val sha: String?,
    val automatic: Boolean
)

/** Link the existing frozen native patch to its originating workspace, including owned fixtures. */
internal object GitHubApprovalBinding {
    data class Continuation(val taskId: String, val chatId: Int, val sourceMessageId: Int, val branch: String, val sha: String, val model: String, val profileContract: String)
    data class Source(val chatId: Int, val messageId: Int, val model: String, val profileContract: String)
    private const val SCOPE = "github-approval-bindings"
    private val revisions = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun observeChanges(): kotlinx.coroutines.flow.StateFlow<Long> = revisions
    private fun key(taskId: String) = "execution:" + GitHubRepositoryStateStore.digest(taskId)

    suspend fun bind(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String, chatId: Int, messageId: Int, model: String = "", profileContract: String = "") {
        val value = buildJsonObject {
            put("schema", "github.approval-binding.v1")
            put("engineering_task_id", taskId)
            put("connection_uid", workspace.connectionUid)
            put("owner", workspace.owner.lowercase(java.util.Locale.ROOT))
            put("repository", workspace.repo.lowercase(java.util.Locale.ROOT))
            put("workspace_branch", workspace.ref)
            put("target_branch", branch)
            put("profile_uid", workspace.mainProfileUid)
            put("chat_id", chatId)
            put("source_message_id", messageId)
            put("status", "WAITING_FOR_APPROVAL")
            put("requested_model", model)
            put("profile_contract", profileContract)
        }
        store.update(SCOPE, key(taskId)) { previous ->
            require(previous == null || matches(previous, taskId, workspace, branch)) { "The saved approval belongs to a different workspace. Open its existing review before continuing." }
            previous ?: value
        }
        revisions.update { it + 1 }
    }

    suspend fun belongs(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String): Boolean =
        store.read(SCOPE, key(taskId))?.let { matches(it, taskId, workspace, branch) } == true

    suspend fun source(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String): Source? {
        val binding = store.read(SCOPE, key(taskId))?.takeIf { matches(it, taskId, workspace, branch) } ?: return null
        return source(binding)
    }

    private fun source(binding: JsonObject): Source? {
        val chatId = (binding["chat_id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return null
        val messageId = (binding["source_message_id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return null
        return Source(chatId, messageId, binding.text("requested_model"), binding.text("profile_contract"))
    }

    suspend fun markDecision(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String, automatic: Boolean) {
        val field = if (automatic) "automatic_approval" else "user_rejected"
        if (!belongs(store, taskId, workspace, branch)) return
        store.update(SCOPE, key(taskId)) { current ->
            val binding = requireNotNull(current)
            require(matches(binding, taskId, workspace, branch))
            JsonObject(binding + (field to JsonPrimitive(true)))
        }
    }

    suspend fun receipt(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String, summary: String, publication: JsonObject?, cancelledBeforePublication: Boolean): GitHubNativeDecisionReceipt? {
        val binding = store.read(SCOPE, key(taskId))?.takeIf { matches(it, taskId, workspace, branch) } ?: return null
        val (chatId, messageId) = source(binding) ?: return null
        val sha = publication?.let { verifiedSha(taskId, branch, it) }
        if (sha == null && (!cancelledBeforePublication || binding["user_rejected"] != JsonPrimitive(true))) return null
        return GitHubNativeDecisionReceipt(taskId, chatId, messageId, branch, summary, sha, sha != null && binding["automatic_approval"] == JsonPrimitive(true))
    }

    /** Rebuild the publication notification from durable native proof in the original chat only. */
    suspend fun continuation(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String, publication: JsonObject): Continuation? {
        val binding = store.read(SCOPE, key(taskId)) ?: return null
        if (!matches(binding, taskId, workspace, branch) || binding["continuation_queued_sha"] != null) return null
        val sha = verifiedSha(taskId, branch, publication) ?: return null
        val (chatId, messageId) = source(binding) ?: return null
        return Continuation(taskId, chatId, messageId, branch, sha, binding.text("requested_model"), binding.text("profile_contract"))
    }

    private fun verifiedSha(taskId: String, branch: String, publication: JsonObject): String? {
        val sha = publication.text("sha")
        if (
            publication.text("engineering_task_id") != taskId ||
            publication.text("branch") != branch ||
            publication.text("approval_state") != "PUBLISHED" ||
            publication["remote_state_verified"] != JsonPrimitive(true) ||
            !sha.matches(Regex("[a-fA-F0-9]{40}")) ||
            !publication.text("parent_sha").matches(Regex("[a-fA-F0-9]{40}"))
        ) {
            return null
        }
        return sha
    }

    fun profileContract(providerType: String, endpoint: String, providerConnection: String?, reasoning: Boolean): String =
        GitHubRepositoryStateStore.digest(listOf(providerType, endpoint, providerConnection.orEmpty(), reasoning.toString()).joinToString("\n"))

    fun matchesProfile(continuation: Continuation, model: String, providerType: String, endpoint: String, providerConnection: String?, reasoning: Boolean): Boolean =
        continuation.model.isNotBlank() &&
            continuation.model == model &&
            continuation.profileContract == profileContract(providerType, endpoint, providerConnection, reasoning)

    /** Mark only after enqueueOnce has retained the turn; a crash before this mark safely deduplicates. */
    suspend fun continuationQueued(store: GitHubRepositoryStateStore, taskId: String, sha: String) {
        require(sha.matches(Regex("[a-fA-F0-9]{40}")))
        store.update(SCOPE, key(taskId)) { current ->
            require(current?.text("engineering_task_id") == taskId)
            require(current["continuation_queued_sha"] == null || current["continuation_queued_sha"] == JsonPrimitive(sha))
            JsonObject(current + ("continuation_queued_sha" to JsonPrimitive(sha)))
        }
    }

    internal fun matches(value: JsonObject, taskId: String, workspace: GitHubConversationContext, branch: String): Boolean =
        value.text("schema") == "github.approval-binding.v1" &&
            value.text("engineering_task_id") == taskId &&
            value.text("connection_uid") == workspace.connectionUid &&
            value.text("owner").equals(workspace.owner, true) &&
            value.text("repository").equals(workspace.repo, true) &&
            value.text("workspace_branch") == workspace.ref &&
            value.text("target_branch") == branch &&
            value.text("profile_uid") == workspace.mainProfileUid

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
