package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
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

    suspend fun originalChat(store: GitHubRepositoryStateStore, taskId: String): Int? = store.read(SCOPE, key(taskId))?.let(::source)?.chatId

    suspend fun automaticallyApproved(store: GitHubRepositoryStateStore, taskId: String): Boolean =
        store.read(SCOPE, key(taskId))?.get("automatic_approval") == JsonPrimitive(true)

    fun isApprovalPause(failure: String?): Boolean = failure != null &&
        (failure.trim() == "Review the saved change to continue." || dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(failure).category == "WAITING_FOR_APPROVAL")

    suspend fun bind(
        store: GitHubRepositoryStateStore,
        taskId: String,
        workspace: GitHubConversationContext,
        branch: String,
        chatId: Int,
        messageId: Int,
        model: String = "",
        profileContract: String = "",
        expectedHeadSha: String? = null,
        patchDigest: String? = null,
        requireSameSource: Boolean = false
    ): String? {
        require(expectedHeadSha == null || expectedHeadSha.matches(Regex("[a-fA-F0-9]{40}")))
        require(patchDigest == null || patchDigest.matches(Regex("[a-fA-F0-9]{64}")))
        val approvalBindingDigest = if (expectedHeadSha != null && patchDigest != null) {
            dedupeKey(chatId, "${workspace.owner}/${workspace.repo}", branch, expectedHeadSha, patchDigest, taskId)
        } else {
            null
        }
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
            expectedHeadSha?.let { put("expected_head_sha", it.lowercase(java.util.Locale.ROOT)) }
            patchDigest?.let { put("patch_digest", it.lowercase(java.util.Locale.ROOT)) }
            approvalBindingDigest?.let { put("approval_binding_sha256", it) }
        }
        val retained = store.update(SCOPE, key(taskId)) { previous ->
            require(previous == null || matches(previous, taskId, workspace, branch, expectedHeadSha, patchDigest)) { "The saved approval belongs to a different workspace or staged patch. Open its existing review before continuing." }
            require(previous == null || !requireSameSource || source(previous) == Source(chatId, messageId, model, profileContract)) { "This patch belongs to another saved request. Its existing approval has been retained." }
            if (previous == null) {
                value
            } else {
                val upgraded = previous.toMutableMap()
                if (expectedHeadSha != null && previous["expected_head_sha"] == null) upgraded["expected_head_sha"] = JsonPrimitive(expectedHeadSha.lowercase(java.util.Locale.ROOT))
                if (patchDigest != null && previous["patch_digest"] == null) upgraded["patch_digest"] = JsonPrimitive(patchDigest.lowercase(java.util.Locale.ROOT))
                if (previous["approval_binding_sha256"] == null) {
                    val head = (upgraded["expected_head_sha"] as? JsonPrimitive)?.contentOrNull
                    val patch = (upgraded["patch_digest"] as? JsonPrimitive)?.contentOrNull
                    if (head != null && patch != null) {
                        val originalSource = requireNotNull(source(previous)) { "The saved approval has no original request identity." }
                        upgraded["approval_binding_sha256"] = JsonPrimitive(dedupeKey(originalSource.chatId, "${workspace.owner}/${workspace.repo}", branch, head, patch, taskId))
                    }
                }
                JsonObject(upgraded)
            }
        }
        revisions.update { it + 1 }
        return retained.text("approval_binding_sha256").takeIf(String::isNotBlank)
    }

    /** An edited patch retains its originating request, never the original patch's approval. */
    suspend fun bindReplacement(
        store: GitHubRepositoryStateStore,
        taskId: String,
        replacementTaskId: String,
        workspace: GitHubConversationContext,
        branch: String,
        expectedHeadSha: String,
        originalPatchDigest: String,
        replacementPatchDigest: String,
        chatId: Int? = null
    ) {
        require(taskId != replacementTaskId) { "The edited patch is unchanged. Its existing review has been retained." }
        val original = requireNotNull(store.read(SCOPE, key(taskId))) { "The original approval binding is unavailable. Its saved review has been retained." }
        require(matches(original, taskId, workspace, branch, expectedHeadSha, originalPatchDigest)) { "The original approval belongs to another workspace or staged patch." }
        val source = requireNotNull(source(original)) { "The original approval has no saved request to continue." }
        require(chatId == null || source.chatId == chatId) { "This approval belongs to another saved chat." }
        bind(
            store, replacementTaskId, workspace, branch, source.chatId, source.messageId,
            source.model, source.profileContract, expectedHeadSha, replacementPatchDigest,
            requireSameSource = true
        )
        if (original["keep_fixture"] == JsonPrimitive(true)) keepFixture(store, replacementTaskId, workspace, branch)
        recordEvent(store, replacementTaskId, "approval_card_created", buildJsonObject { put("replaces_task_id", taskId) })
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

    suspend fun markDecision(
        store: GitHubRepositoryStateStore,
        taskId: String,
        workspace: GitHubConversationContext,
        branch: String,
        automatic: Boolean,
        cancellationConfirmed: Boolean = false
    ) {
        val field = if (automatic) "automatic_approval" else "user_rejected"
        if (!belongs(store, taskId, workspace, branch)) return
        store.update(SCOPE, key(taskId)) { current ->
            val binding = requireNotNull(current)
            require(matches(binding, taskId, workspace, branch))
            val withDecision = JsonObject(
                binding + buildMap {
                    put(field, JsonPrimitive(true))
                    if (!automatic && cancellationConfirmed) put("cancellation_confirmed", JsonPrimitive(true))
                }
            )
            appendEvent(
                withDecision,
                if (automatic) "approval_card_approved" else "approval_card_rejected",
                buildJsonObject {
                    put("decision_source", if (automatic) "AUTOMATIC_POLICY" else "USER")
                    if (!automatic) put("cancellation_confirmed", cancellationConfirmed)
                }
            )
        }
        revisions.update { it + 1 }
    }

    /** App-side cancellation proof resolves only the exact staged patch; it is not remote-state evidence. */
    suspend fun localCancellationReceipt(
        store: GitHubRepositoryStateStore,
        workspace: GitHubConversationContext,
        stagedReceipt: JsonObject
    ): JsonObject? {
        val taskId = GitHubPendingApproval.taskId(stagedReceipt) ?: return null
        val stagedArguments = stagedReceipt["arguments"] as? JsonObject ?: return null
        val stagedResult = (stagedReceipt["result"] as? JsonObject)?.let { it["data"] as? JsonObject ?: it } ?: return null
        val branch = stagedArguments.text("branch").ifBlank { stagedResult.text("branch") }
        val expectedHead = stagedArguments.text("expected_head_sha").ifBlank { stagedResult.text("expected_head_sha") }
        val patchDigest = stagedResult.text("patch_digest").ifBlank { stagedArguments.text("patch_digest") }
        val root = stagedReceipt.text("repository")
        val binding = store.read(SCOPE, key(taskId)) ?: return null
        val chatId = (binding["chat_id"] as? JsonPrimitive)?.intOrNull ?: return null
        val approvalBindingDigest = dedupeKey(chatId, root, branch, expectedHead, patchDigest, taskId)
        if (
            branch.isBlank() ||
            !expectedHead.matches(Regex("[a-fA-F0-9]{40}")) ||
            !patchDigest.matches(Regex("[a-fA-F0-9]{64}")) ||
            root != "${workspace.owner}/${workspace.repo}" &&
            !root.equals("${workspace.owner}/${workspace.repo}", true) ||
            binding["user_rejected"] != JsonPrimitive(true) ||
            binding["cancellation_confirmed"] != JsonPrimitive(true) ||
            binding.text("expected_head_sha") != expectedHead ||
            binding.text("patch_digest") != patchDigest ||
            binding.text("approval_binding_sha256").let { it.isNotBlank() && it != approvalBindingDigest } ||
            stagedResult["approval_binding_sha256"]?.let { it != JsonPrimitive(approvalBindingDigest) } == true ||
            !matches(binding, taskId, workspace, branch, expectedHead, patchDigest) ||
            stagedResult["chat_id"] != binding["chat_id"] ||
            stagedResult["source_message_id"]?.let { it != binding["source_message_id"] } == true ||
            stagedReceipt["binding"] == null ||
            stagedReceipt["binding"] == JsonPrimitive("")
        ) {
            return null
        }
        val rejection = events(store, taskId).lastOrNull { it.text("event") == "approval_card_rejected" } ?: return null
        val eventTime = (rejection["at_epoch_ms"] as? JsonPrimitive)?.contentOrNull ?: return null
        val receiptId = "local_approval_cancel_" + GitHubRepositoryStateStore.digest("${stagedReceipt.text("binding")}|$taskId|$expectedHead|$patchDigest").take(24)
        return buildJsonObject {
            put("tool", "github_context")
            put("call_id", receiptId)
            put("receipt_id", receiptId)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "recovery")
                    put("engineering_task_id", taskId)
                    put("branch", branch)
                }
            )
            put("binding", stagedReceipt["binding"]!!)
            put("repository", root)
            put("success", true)
            put("trusted", true)
            put("verification_eligible", true)
            put("evidence_origin", "LOCAL_DURABLE_APPROVAL_EVENT")
            put("local_resolution_event", "approval_card_rejected")
            put(
                "result",
                buildJsonObject {
                    put("engineering_task_id", taskId)
                    put("branch", branch)
                    put("expected_head_sha", expectedHead)
                    put("patch_digest", patchDigest)
                    put("approval_binding_sha256", approvalBindingDigest)
                    put("approval_state", "CANCELLED_BEFORE_PUBLICATION")
                    put("native_task_state", "CANCELLED")
                    put("cancellation_confirmed", true)
                    put("remote_mutation_performed", false)
                    put("remote_state_verified", false)
                    put("source_event_at_epoch_ms", eventTime)
                    put("keep_fixture", binding["keep_fixture"] == JsonPrimitive(true))
                }
            )
        }
    }

    suspend fun localFixtureDecisionReceipt(
        store: GitHubRepositoryStateStore,
        workspace: GitHubConversationContext,
        stagedReceipt: JsonObject
    ): JsonObject? {
        val taskId = GitHubPendingApproval.taskId(stagedReceipt) ?: return null
        val arguments = stagedReceipt["arguments"] as? JsonObject ?: return null
        val result = (stagedReceipt["result"] as? JsonObject)?.let { it["data"] as? JsonObject ?: it } ?: return null
        val branch = arguments.text("branch").ifBlank { result.text("branch") }
        val expectedHead = arguments.text("expected_head_sha").ifBlank { result.text("expected_head_sha") }
        val patchDigest = result.text("patch_digest").ifBlank { arguments.text("patch_digest") }
        val binding = store.read(SCOPE, key(taskId)) ?: return null
        val chatId = (binding["chat_id"] as? JsonPrimitive)?.intOrNull ?: return null
        val approvalBindingDigest = dedupeKey(chatId, stagedReceipt.text("repository"), branch, expectedHead, patchDigest, taskId)
        if (
            binding["keep_fixture"] != JsonPrimitive(true) ||
            !branch.matches(Regex("codex-self-test/[a-f0-9]{20}")) ||
            !expectedHead.matches(Regex("[a-fA-F0-9]{40}")) ||
            !patchDigest.matches(Regex("[a-fA-F0-9]{64}")) ||
            binding.text("expected_head_sha") != expectedHead ||
            binding.text("patch_digest") != patchDigest ||
            binding.text("approval_binding_sha256").let { it.isNotBlank() && it != approvalBindingDigest } ||
            result["approval_binding_sha256"]?.let { it != JsonPrimitive(approvalBindingDigest) } == true ||
            !matches(binding, taskId, workspace, branch, expectedHead, patchDigest) ||
            stagedReceipt.text("repository").let { !it.equals("${workspace.owner}/${workspace.repo}", true) } ||
            result["chat_id"] != binding["chat_id"] ||
            result["source_message_id"]?.let { it != binding["source_message_id"] } == true
        ) {
            return null
        }
        val event = events(store, taskId).lastOrNull { it.text("event") == "approval_card_keep_fixture" } ?: return null
        val eventTime = (event["at_epoch_ms"] as? JsonPrimitive)?.contentOrNull ?: return null
        val receiptId = "local_fixture_keep_" + GitHubRepositoryStateStore.digest("${stagedReceipt.text("binding")}|$taskId|$expectedHead|$patchDigest").take(24)
        return buildJsonObject {
            put("tool", "github_context")
            put("call_id", receiptId)
            put("receipt_id", receiptId)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "local_fixture_decision")
                    put("engineering_task_id", taskId)
                    put("branch", branch)
                }
            )
            put("binding", stagedReceipt["binding"]!!)
            put("repository", stagedReceipt["repository"]!!)
            put("success", true)
            put("trusted", true)
            put("verification_eligible", true)
            put("evidence_origin", "LOCAL_DURABLE_APPROVAL_EVENT")
            put("local_resolution_event", "approval_card_keep_fixture")
            put(
                "result",
                buildJsonObject {
                    put("engineering_task_id", taskId)
                    put("branch", branch)
                    put("expected_head_sha", expectedHead)
                    put("patch_digest", patchDigest)
                    put("approval_binding_sha256", approvalBindingDigest)
                    put("fixture_retention_state", "KEEP_BY_USER")
                    put("keep_fixture", true)
                    put("source_event_at_epoch_ms", eventTime)
                }
            )
        }
    }

    suspend fun cancellationConfirmed(store: GitHubRepositoryStateStore, taskId: String): Boolean =
        store.read(SCOPE, key(taskId))?.let {
            it["user_rejected"] == JsonPrimitive(true) && it["cancellation_confirmed"] == JsonPrimitive(true)
        } == true

    suspend fun cancellationContinuationQueued(store: GitHubRepositoryStateStore, taskId: String): Boolean =
        store.read(SCOPE, key(taskId))?.get("cancellation_continuation_queued") == JsonPrimitive(true)

    suspend fun markCancellationContinuationQueued(store: GitHubRepositoryStateStore, taskId: String) {
        store.update(SCOPE, key(taskId)) { current ->
            require(current?.text("engineering_task_id") == taskId)
            appendEvent(JsonObject(current + ("cancellation_continuation_queued" to JsonPrimitive(true))), "approval_cleanup_continuation_queued", JsonObject(emptyMap()))
        }
        revisions.update { it + 1 }
    }

    suspend fun recordEvent(store: GitHubRepositoryStateStore, taskId: String, event: String, details: JsonObject = JsonObject(emptyMap())) {
        require(event in setOf("approval_card_created", "approval_card_shown", "approval_card_clicked", "approval_card_approved", "approval_card_rejected", "approval_card_expired", "approval_card_keep_fixture", "approval_cleanup_continuation_queued"))
        store.update(SCOPE, key(taskId)) { current ->
            val binding = requireNotNull(current) { "The approval card no longer has a saved task binding." }
            appendEvent(binding, event, details)
        }
        revisions.update { it + 1 }
    }

    suspend fun keepFixture(store: GitHubRepositoryStateStore, taskId: String, workspace: GitHubConversationContext, branch: String) {
        if (!belongs(store, taskId, workspace, branch)) return
        store.update(SCOPE, key(taskId)) { current ->
            val binding = requireNotNull(current)
            require(matches(binding, taskId, workspace, branch))
            appendEvent(JsonObject(binding + ("keep_fixture" to JsonPrimitive(true))), "approval_card_keep_fixture", buildJsonObject { put("branch", branch) })
        }
        revisions.update { it + 1 }
    }

    suspend fun fixtureKept(store: GitHubRepositoryStateStore, taskId: String): Boolean =
        store.read(SCOPE, key(taskId))?.get("keep_fixture") == JsonPrimitive(true)

    suspend fun events(store: GitHubRepositoryStateStore, taskId: String): List<JsonObject> =
        (store.read(SCOPE, key(taskId))?.get("approval_events") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun appendEvent(binding: JsonObject, event: String, details: JsonObject): JsonObject {
        val events = (binding["approval_events"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val entry = buildJsonObject {
            put("event", event)
            put("engineering_task_id", binding.text("engineering_task_id"))
            put("card_id", "github-approval-${binding.text("engineering_task_id")}")
            put("at_epoch_ms", System.currentTimeMillis())
            put("details", details)
        }
        return JsonObject(binding + ("approval_events" to JsonArray((events + entry).takeLast(64))))
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
        revisions.update { it + 1 }
    }

    internal fun matches(
        value: JsonObject,
        taskId: String,
        workspace: GitHubConversationContext,
        branch: String,
        expectedHeadSha: String? = null,
        patchDigest: String? = null
    ): Boolean =
        value.text("schema") == "github.approval-binding.v1" &&
            value.text("engineering_task_id") == taskId &&
            value.text("connection_uid") == workspace.connectionUid &&
            value.text("owner").equals(workspace.owner, true) &&
            value.text("repository").equals(workspace.repo, true) &&
            value.text("workspace_branch") == workspace.ref &&
            value.text("target_branch") == branch &&
            value.text("profile_uid") == workspace.mainProfileUid &&
            (expectedHeadSha == null || value.text("expected_head_sha").isBlank() || value.text("expected_head_sha").equals(expectedHeadSha, true)) &&
            (patchDigest == null || value.text("patch_digest").isBlank() || value.text("patch_digest").equals(patchDigest, true))

    internal fun dedupeKey(chatId: Int, repository: String, branch: String, expectedHeadSha: String, patchDigest: String, taskId: String): String =
        GitHubRepositoryStateStore.digest(listOf(chatId.toString(), repository.lowercase(java.util.Locale.ROOT), branch, expectedHeadSha.lowercase(java.util.Locale.ROOT), patchDigest.lowercase(java.util.Locale.ROOT), taskId).joinToString("\n"))

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
