package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit same-writer retries keep the accepted corpus and fund exactly one new final stage. */
internal object CombinedWriterRetrySnapshot {
    fun prepare(original: CombinedInputSnapshot, writer: PlatformV2, userOutputLimit: Int? = null, userContextLimit: Int? = null): CombinedInputSnapshot {
        original.bindAcceptedStage()
        require(original.version >= 6 && writer.uid == original.profileUid && writer.model == original.model) { "COMBINED_RETRY_WRITER_CHANGED" }
        original.writerRequestPlan?.validateProfile(writer)
        val policy = requireNotNull(original.combined) { "COMBINED_RETRY_POLICY_UNAVAILABLE" }
        val nextContext = policy.forExplicitWriterRetry(writer.uid)
        val revision = UUID.randomUUID().toString()
        val prompt = retryPrompt(original.prompt, revision, nextContext)
        val plan = CombinedWriterRequestPlan.freeze(
            writer, original.userMessage.content, prompt,
            combinedWriterSystemPrompt(original.systemPrompt, original.deliveryProtocol, original.userMessage.content, nextContext.requested.appendContinuations),
            original.writerContext?.historyText() ?: combinedWriterHistory(original.history, original.replies, original.profileUid),
            original.deliveryProtocol, nextContext.plan?.finalOutputTokens ?: 0, userOutputLimit, userContextLimit, original.writerContext,
            appendContinuations = nextContext.requested.appendContinuations
        )
        return original.copy(
            snapshotId = UUID.randomUUID().toString(),
            predecessorSnapshotId = original.snapshotId,
            inputDigest = "",
            revisionId = revision,
            combined = nextContext,
            acceptedTools = original.acceptedTools?.copy(combined = nextContext),
            resumeFromRunId = null,
            deliveryResumeFromRevisionId = null,
            predecessorRunId = null,
            committedPrefix = "",
            dispatchIdentity = combinedDigest(original.dispatchIdentity + ":explicit-retry:" + nextContext.rootBudgetId),
            prompt = prompt,
            writerRequestPlan = plan
        ).seal().also { validate(original, it) }
    }

    fun validate(original: CombinedInputSnapshot, retry: CombinedInputSnapshot) {
        original.bindAcceptedStage()
        retry.bindAcceptedStage()
        val originalPolicy = requireNotNull(original.combined) { "COMBINED_RETRY_POLICY_UNAVAILABLE" }
        val retryPolicy = requireNotNull(retry.combined) { "COMBINED_RETRY_POLICY_UNAVAILABLE" }
        require(original.version >= 6 && originalPolicy.requested.singlePass && retry.profileUid == original.profileUid) { "COMBINED_RETRY_WRITER_CHANGED" }
        require(
            retry.snapshotId.isNotBlank() &&
                retry.snapshotId != original.snapshotId &&
                retry.predecessorSnapshotId == original.snapshotId &&
                retry.revisionId.isNotBlank() &&
                retry.revisionId != original.revisionId &&
                retryPolicy.rootBudgetId.isNotBlank() &&
                retryPolicy.rootBudgetId != originalPolicy.rootBudgetId &&
                retry.dispatchIdentity.isNotBlank() &&
                retry.dispatchIdentity != original.dispatchIdentity
        ) { "COMBINED_EXPLICIT_WRITER_REQUIRES_FRESH_ROOT" }
        require(retry.resumeFromRunId == null && retry.deliveryResumeFromRevisionId == null && retry.predecessorRunId == null && retry.committedPrefix.isEmpty()) { "COMBINED_RETRY_CANNOT_RESUME_PARTIAL" }
        require(retryPolicy.copy(rootBudgetId = "") == originalPolicy.forExplicitWriterRetry(original.profileUid).copy(rootBudgetId = "")) { "COMBINED_RETRY_POLICY_CHANGED" }
        require(retry.acceptedTools == original.acceptedTools?.copy(combined = retryPolicy)) { "COMBINED_RETRY_POLICY_CHANGED" }
        require(retry.prompt == retryPrompt(original.prompt, retry.revisionId, retryPolicy)) { "COMBINED_RETRY_PROMPT_CHANGED" }
        require(retry.writerRequestPlan != null && (original.writerRequestPlan == null || retry.writerRequestPlan.routeRevision == original.writerRequestPlan.routeRevision)) { "COMBINED_WRITER_PLAN_ROUTE_CHANGED" }
        require(
            retry.copy(
                snapshotId = original.snapshotId,
                predecessorSnapshotId = original.predecessorSnapshotId,
                inputDigest = original.inputDigest,
                revisionId = original.revisionId,
                combined = original.combined,
                acceptedTools = original.acceptedTools,
                resumeFromRunId = original.resumeFromRunId,
                deliveryResumeFromRevisionId = original.deliveryResumeFromRevisionId,
                predecessorRunId = original.predecessorRunId,
                committedPrefix = original.committedPrefix,
                dispatchIdentity = original.dispatchIdentity,
                prompt = original.prompt,
                writerRequestPlan = original.writerRequestPlan
            ) == original
        ) { "COMBINED_RETRY_CORPUS_CHANGED" }
    }

    private fun retryPrompt(prompt: String, revisionId: String, policy: CombinedExecutionContext): String {
        val original = Json.parseToJsonElement(prompt) as JsonObject
        val updated = original.toMutableMap()
        updated["target_revision_id"] = JsonPrimitive(revisionId)
        if ("requested_policy_digest" in original) updated["requested_policy_digest"] = JsonPrimitive(policy.requested.requestedPolicyDigest)
        if ("effective_plan_digest" in original) updated["effective_plan_digest"] = JsonPrimitive(policy.plan?.effectivePlanDigest.orEmpty())
        return JsonObject(updated).toString()
    }
}
