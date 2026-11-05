package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Resume a complete, output-limited text segment without taking tools away from unfinished work. */
internal class GitHubOutputContinuationSession(
    private val delegate: AgentProviderSession,
    private val maxContinuations: Int = 2,
    private val maxCharacters: Int = 1_048_576,
    private val continuationRevision: String = "",
    private val readCheckpoint: suspend () -> JsonObject? = { null },
    private val saveCheckpoint: suspend (JsonObject) -> Unit = {},
    private val reopen: suspend (String, List<AgentToolExchange>, List<AgentToolDefinition>) -> AgentProviderSession
) : AgentProviderSession by delegate {
    private var currentCheckpoint: JsonObject? = null

    init {
        require(maxContinuations in 0..4)
        require(maxCharacters in 1..1_048_576)
    }

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (delegate.handlesToolsInternally) {
            emitAll(delegate.streamRound(tools, exchanges))
            return@flow
        }
        val evidenceDigest = GitHubEvidenceBook.digest(
            exchanges.joinToString("\n") { exchange ->
                exchange.calls.joinToString { "${it.callId}:${it.name}:${it.arguments}" } + "\n" + exchange.results.joinToString { "${it.callId}:${it.isError}:${it.dispatched}:${it.content}" }
            }
        )
        val previous = readCheckpoint() ?: currentCheckpoint
        val sameEvidence = previous?.get("evidence_digest") == JsonPrimitive(evidenceDigest)
        val revised = previous != null && (previous["continuation_revision"] ?: JsonPrimitive("")) != JsonPrimitive(continuationRevision)
        if (sameEvidence && previous?.get("state") == JsonPrimitive("BLOCKED_NO_PROGRESS") && !revised) {
            emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The saved draft is blocked because its unchanged continuation added no new work. Change the request or supported output configuration before resuming.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED"))
            return@flow
        }
        val handoff = previous?.takeIf {
            it["state"] == JsonPrimitive("TOOL_HANDOFF") &&
                (it["draft"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        }
        if (handoff != null && !handoffResolved(handoff, exchanges)) {
            emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_PENDING_TOOLS: Reconcile the saved tool batch before continuing its draft. The completed response has not been regenerated and no tool actions were repeated.", errorCode = "OUTPUT_CONTINUATION_PENDING_TOOLS", taskOutcome = "BLOCKED"))
            return@flow
        }
        val restored = handoff ?: previous?.takeIf {
            sameEvidence &&
                (it["state"] == JsonPrimitive("OUTPUT_LIMIT") || it["resume_allowed"] == JsonPrimitive(true) || revised && it["state"] == JsonPrimitive("BLOCKED_NO_PROGRESS"))
        }
        var draft = (restored?.get("draft") as? JsonPrimitive)?.contentOrNull.orEmpty()
        var attempts = if (revised) 0 else (restored?.get("continuations") as? JsonPrimitive)?.intOrNull ?: 0
        require(draft.length <= maxCharacters && attempts in 0..maxContinuations) { "Saved GitHub continuation is invalid; no generation was dispatched." }
        var active = delegate
        var needsContinuation = restored != null
        var claimContinuation = handoff == null && restored?.get("resume_kind") != JsonPrimitive("AFTER_TOOL_RESULTS")

        suspend fun save(state: String, text: String = draft, resumable: Boolean = state == "OUTPUT_LIMIT", pendingCalls: List<ProviderEvent.ToolCall> = emptyList()) {
            val checkpoint = buildJsonObject {
                put("schema", "github.output-checkpoint.v1")
                put("evidence_digest", evidenceDigest)
                put("state", state)
                put("draft", text)
                put("continuations", attempts)
                put("continuation_revision", continuationRevision)
                put("resume_kind", if (claimContinuation) "OUTPUT_CONTINUATION" else "AFTER_TOOL_RESULTS")
                put("resume_allowed", resumable)
                put("tool_actions_replayed", false)
                if (pendingCalls.isNotEmpty()) put("pending_calls", JsonArray(pendingCalls.map { JsonPrimitive(callDigest(it)) }))
            }
            saveCheckpoint(checkpoint)
            currentCheckpoint = checkpoint
        }

        while (true) {
            if (needsContinuation) {
                if (claimContinuation && attempts >= maxContinuations) {
                    save("OUTPUT_LIMIT")
                    emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_BUDGET_EXHAUSTED: The saved GitHub draft and operation receipts are retained after $attempts continuation requests. Narrow the remaining request or increase the supported output capacity.", errorCode = "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED", taskOutcome = "BLOCKED"))
                    return@flow
                }
                if (claimContinuation) attempts++
                // Claim before dispatch so process death cannot replenish the allowance.
                save("OUTPUT_LIMIT")
                emit(ProviderEvent.Notice(if (claimContinuation) "Continuing the saved GitHub draft ($attempts/$maxContinuations); unfinished operations keep their authorized tools." else "Resuming the saved GitHub draft with the confirmed tool results.", false))
                active = try {
                    reopen(draft, exchanges, tools)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_UNAVAILABLE: The saved GitHub draft could not fit the current context or reach its provider. Draft and operation receipts remain available.", errorCode = "OUTPUT_CONTINUATION_UNAVAILABLE", taskOutcome = "PARTIAL"))
                    return@flow
                }
                check(!active.handlesToolsInternally) { "GitHub continuation requires client-owned tools; reconcile server-owned actions before resuming." }
            }
            val segment = StringBuilder()
            val calls = mutableListOf<ProviderEvent.ToolCall>()
            val admission = ToolRoundAdmission()
            var completed = false
            var finishReason: String? = null
            var failure: ProviderEvent.Failed? = null
            var receivedToolResult = false
            var savedAt = 0
            try {
                active.streamRound(tools, if (needsContinuation) emptyList() else exchanges).stopAtUnknownGatewayOutcome().withSummedRequestUsage().collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            check(draft.length.toLong() + segment.length + event.text.length <= maxCharacters) { "GitHub draft exceeded its retention allowance; completed evidence remains saved." }
                            segment.append(event.text)
                            if (segment.length - savedAt >= 4096) {
                                save("WRITING", draft + continuationSuffix(draft, segment.toString()), resumable = calls.isEmpty() && !receivedToolResult)
                                savedAt = segment.length
                            }
                        }
                        is ProviderEvent.ToolCall -> {
                            if (!admission.admit(event)) throw ToolBatchOversizeException()
                            calls += event
                            save("TOOL_CALL_BUFFERED", draft + continuationSuffix(draft, segment.toString()))
                        }
                        is ProviderEvent.ToolResult -> {
                            receivedToolResult = true
                            save("TOOL_RESULT_OBSERVED", draft + continuationSuffix(draft, segment.toString()))
                            emit(event)
                        }
                        is ProviderEvent.Usage -> {
                            finishReason = event.finishReason ?: finishReason
                            emit(event)
                        }
                        is ProviderEvent.Failed -> if (failure == null) failure = event
                        ProviderEvent.Completed -> completed = true
                        else -> emit(event)
                    }
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    try {
                        save(
                            if (error is CancellationException) "CANCELLED" else "INTERRUPTED",
                            draft + continuationSuffix(draft, segment.toString()),
                            resumable = calls.isEmpty() && !receivedToolResult && (draft.isNotBlank() || segment.isNotBlank()) && !GatewayOutcomeRecovery.requiresReconciliation(error.message.orEmpty())
                        )
                    } catch (storage: Exception) {
                        error.addSuppressed(storage)
                    }
                }
                throw error
            }
            val novel = if (needsContinuation) continuationSuffix(draft, segment.toString()) else segment.toString()
            draft += novel
            val limited = finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") || failure?.let { isProviderOutputLimitFailure(it.message) } == true
            val safeBoundary = !receivedToolResult &&
                calls.isEmpty() &&
                failure?.providerLimit == null &&
                failure?.errorCode?.startsWith("TOOL_PROTOCOL_") != true &&
                !GatewayOutcomeRecovery.requiresReconciliation(failure?.message.orEmpty(), failure?.errorCode) &&
                (failure?.transportComplete == true || failure == null && completed)
            if (limited && safeBoundary && needsContinuation && novel.isBlank()) {
                save("BLOCKED_NO_PROGRESS")
                emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The provider exhausted its output without adding to the saved draft. Change the request or supported output capacity before resuming.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED"))
                return@flow
            }
            if (limited && safeBoundary && draft.isNotBlank() && novel.isNotBlank()) {
                needsContinuation = true
                claimContinuation = true
                save("OUTPUT_LIMIT")
                continue
            }
            if (failure != null || limited || !completed) {
                val mayResumeText = !receivedToolResult &&
                    calls.isEmpty() &&
                    draft.isNotBlank() &&
                    failure?.errorCode?.startsWith("TOOL_PROTOCOL_") != true &&
                    !GatewayOutcomeRecovery.requiresReconciliation(failure?.message.orEmpty(), failure?.errorCode)
                save(if (limited && safeBoundary) "OUTPUT_LIMIT" else "INTERRUPTED", resumable = mayResumeText)
                // Incomplete frames stay with the runner's protocol repair; no buffered call is dispatched.
                emit(failure ?: ProviderEvent.Failed("The GitHub draft did not reach a complete response boundary. Saved text and receipts remain available.", errorCode = if (limited) "OUTPUT_LIMIT" else "INCOMPLETE_PROVIDER_STREAM"))
                return@flow
            }
            if (needsContinuation && calls.isEmpty() && novel.isBlank()) {
                save("BLOCKED_NO_PROGRESS")
                emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The continuation repeated the saved draft without new work. Saved evidence remains available.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED"))
                return@flow
            }
            save(if (calls.isEmpty()) "COMPLETED" else "TOOL_HANDOFF", pendingCalls = calls)
            if (calls.isEmpty() && draft.isNotEmpty()) emit(ProviderEvent.TextDelta(draft))
            calls.forEach { emit(it) }
            emit(ProviderEvent.Completed)
            return@flow
        }
    }

    private fun handoffResolved(checkpoint: JsonObject, exchanges: List<AgentToolExchange>): Boolean {
        val pending = (checkpoint["pending_calls"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return false
        if (pending.isEmpty()) return false
        val resolved = exchanges.flatMap { exchange ->
            exchange.calls.filter { call ->
                exchange.results.any { result ->
                    result.callId == call.callId &&
                        result.errorCode !in ToolResultEnvelope.unknownOutcomeCodes &&
                        result.executionReceipt?.get("requires_reconciliation") != JsonPrimitive(true) &&
                        !GatewayOutcomeRecovery.requiresReconciliation("", result.errorCode)
                }
            }.map { it.originalInvocationFingerprint ?: callDigest(it) }
        }.toSet()
        return resolved.containsAll(pending)
    }

    private fun callDigest(call: ProviderEvent.ToolCall): String = GitHubEvidenceBook.digest("${call.callId}:${call.name}:${call.arguments}")
}
