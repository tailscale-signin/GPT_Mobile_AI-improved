package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An in-memory witness for this invocation only; saved receipts cannot recreate it. */
internal class PhoneIndexInterruption(private val call: ProviderEvent.ToolCall) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PhoneIndexInterruption> {
        const val CODE = "PHONE_INDEX_INTERRUPTED"
        private const val SCHEMA = "gptmobile.phone-index-interruption.v1"

        fun scopeHash(scope: GitHubConversationContext, account: String) = GitHubRepositoryStateStore.digest(
            buildJsonObject {
                put("account", account)
                put("connection", scope.connectionUid)
                put("profile", scope.mainProfileUid)
                put("repository", scope.repository.fullName)
                put("ref", scope.ref)
                put("commit", scope.commitSha)
            }.toString()
        )
    }

    private var attempt: Attempt? = null
    private var receipt: AgentToolResult? = null
    private var closed = false
    private var conflicting = false

    /** Only another budget wrapper for this still-active physical call can share its witness. */
    @Synchronized fun reuseFor(observedCall: ProviderEvent.ToolCall, samePhysicalOwner: Boolean): PhoneIndexInterruption? =
        takeIf { samePhysicalOwner && !closed && !conflicting && observedCall == call }

    @Synchronized fun begin(callId: String, name: String, arguments: JsonObject, scope: GitHubConversationContext?, account: String?): Attempt? {
        if (closed ||
            call.callId != callId ||
            call.name != name ||
            call.arguments != arguments ||
            name.substringBefore("__") != "repository_graph" ||
            arguments["action"] != JsonPrimitive("index") ||
            arguments["map_source"] !in setOf(null, JsonPrimitive("phone")) ||
            scope == null ||
            account.isNullOrBlank()
        ) {
            return null
        }
        // Two executions cannot share one cancellation witness, even with the same call ID.
        if (attempt != null) {
            conflicting = true
            receipt = null
            return null
        }
        return Attempt(scope, account, arguments).also { attempt = it }
    }

    /** Closing before delivery also rejects any late or competing callback. */
    @Synchronized fun close(): AgentToolResult? {
        closed = true
        return receipt.takeUnless { conflicting }
    }

    @Synchronized private fun retain(owner: Attempt, result: AgentToolResult) {
        if (!closed && !conflicting && owner === attempt && result.callId == call.callId) receipt = result
    }

    @Synchronized private fun invalidate(owner: Attempt) {
        if (!closed && owner === attempt) {
            conflicting = true
            receipt = null
        }
    }

    /** Installed by the native facade and admitted only by the actual phone repository. */
    inner class Attempt internal constructor(
        private val scope: GitHubConversationContext,
        private val account: String,
        arguments: JsonObject
    ) : AbstractCoroutineContextElement(AttemptKey) {
        private val payload = JsonObject(
            (arguments["options"] as? JsonObject).orEmpty() + (arguments - "options" - "map_source") +
                ("budget_bytes" to JsonPrimitive(16000))
        )
        private var admitted = false
        private var invalid = false
        private var finished = false

        @Synchronized fun admit(observedScope: GitHubConversationContext, observedAccount: String, arguments: JsonObject) {
            if (admitted || finished || observedScope != scope || observedAccount != account || arguments != payload) {
                invalid = true
                invalidate(this)
                return
            }
            admitted = true
        }

        /** Called only after the facade's structured query and all its children have unwound. */
        @Synchronized fun cancelledAfterUnwind(accounting: GitHubDispatchAccounting) {
            if (!admitted || invalid || finished) {
                invalidate(this)
                return
            }
            finished = true
            val candidate = AgentToolResult(
                call.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", SCHEMA)
                        put("status", "INTERRUPTED")
                        put("map_source", "phone")
                        put("repository", scope.repository.fullName)
                        put("branch", scope.ref)
                        put("commit_sha", scope.commitSha)
                        put("completion", "NOT_OBSERVED")
                        put("progress", "READBACK_REQUIRED")
                        put("requires_reconciliation", false)
                        put("message", "The foreground phone index stopped. Completed local source may be retained. Read the current phone status under its normal authorization, then use the exact issued index_cursor to prepare any remaining source. This receipt does not establish complete coverage or settle another operation.")
                        put(
                            "recovery_call",
                            buildJsonObject {
                                put("tool", call.name)
                                put(
                                    "arguments",
                                    buildJsonObject {
                                        put("action", "status")
                                        put("map_source", "phone")
                                    }
                                )
                            }
                        )
                    }
                ),
                isError = true,
                errorCode = CODE,
                dispatched = true
            )
            val observed = accounting.finish(candidate, mutation = false)
            val execution = observed.executionReceipt ?: return
            if (observed.errorCode != CODE ||
                listOf("mutation_requests", "accepted_mutations", "rejected_mutations", "unknown_mutations").any { execution[it] != JsonPrimitive(0) } ||
                execution["requires_reconciliation"] != JsonPrimitive(false)
            ) {
                invalidate(this)
                return
            }
            retain(
                this,
                candidate.copy(
                    executionReceipt = JsonObject(
                        execution + buildJsonObject {
                            put("schema", SCHEMA)
                            put("call_id", call.callId)
                            put("tool", call.name)
                            put("action", "index")
                            put("map_source", "phone")
                            put("phase", "CANCELLED_LOCALLY")
                            put("local_work_stopped", true)
                            put("requires_reconciliation", false)
                            put("replay_safe", false)
                            put("retryable", false)
                            put("request_replay_allowed", false)
                            put("authorization_recheck_required", true)
                            put("source_head_sha", scope.commitSha)
                            put("scope_hash", scopeHash(scope, account))
                        }
                    )
                )
            )
        }
    }

    object AttemptKey : CoroutineContext.Key<PhoneIndexInterruption.Attempt>
}
