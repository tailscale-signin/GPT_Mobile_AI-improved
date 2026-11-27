package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One terminal delivery attempt per run collection; a later user resume gets a new wrapper. */
internal fun AgentProviderSession.withSingleRetainedFailureResponse(): AgentProviderSession {
    val attempted = AtomicBoolean(false)
    return object : AgentProviderSession by this {
        override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = flow {
            if (!attempted.compareAndSet(false, true) || !failure.mayRequestRetainedReport()) {
                emit(failure)
                return@flow
            }
            emitAll(this@withSingleRetainedFailureResponse.retainedFailureResponse(failure))
        }
    }
}

/** A terminal delivery boundary, after recovery; it never invokes a model or a tool. */
internal fun Flow<AgentRunEvent>.withGitHubTerminalDelivery(enabled: Boolean, retainedResponse: ((ProviderEvent.Failed) -> Flow<ProviderEvent>)? = null): Flow<AgentRunEvent> {
    if (!enabled) return this
    val upstream = this
    return flow {
        var visible = false
        var terminal = false
        var restoredResults = 0
        val receipts = linkedSetOf<String>()
        val observations = linkedMapOf<String, kotlinx.serialization.json.JsonObject>()
        suspend fun finish(originalFailure: ProviderEvent.Failed) {
            if (terminal) return
            var failure = originalFailure
            if (!visible && retainedResponse != null && failure.mayRequestRetainedReport()) {
                // Raw runner exits (including failed connectivity waits) still have
                // the accepted session's authorized saved report available. Ask that
                // receipt-only boundary before replacing it with a generic message.
                retainedResponse(failure).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            visible = visible || event.text.isNotBlank()
                            emit(AgentRunEvent.Provider(event))
                        }
                        is ProviderEvent.Failed -> failure = event
                        is ProviderEvent.Completed -> if (visible) {
                            terminal = true
                            emit(AgentRunEvent.Provider(event))
                        }
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> error("Terminal retained delivery cannot dispatch operations")
                        else -> emit(AgentRunEvent.Provider(event))
                    }
                }
                if (terminal) return
            }
            // A typed rejection can be saved for the existing provider wait. Do not
            // turn a no-output attempt into an answer before its durable safety check.
            if (!visible && failure.providerLimit?.mayWait != true) {
                val reason = failure.errorCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "GITHUB_RUN_INTERRUPTED"
                val evidence = when {
                    restoredResults > 0 -> "This run restored $restoredResults saved tool ${if (restoredResults == 1) "result" else "results"} before it stopped."
                    receipts.isNotEmpty() -> "${receipts.size} tool ${if (receipts.size == 1) "result was" else "results were"} recorded before this run stopped."
                    else -> "No tool result was delivered for this attempt."
                }
                val established = if (failure.blocksPublication()) "" else dev.chungjungsoo.gptmobile.data.github.GitHubPartialReport.render(observations.values)
                val stage = (failure.failureDetails?.get("failed_stage") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
                val nextAction = (failure.failureDetails?.get("next_action") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.matches(Regex("[A-Z_]{1,96}")) }
                // A successful read is retained evidence, not a completed task.
                // Only an explicit task result may establish a partial outcome.
                val blocked = failure.taskOutcome == "BLOCKED" || failure.errorCode in ProviderRecoveryFailure.CODES || failure.errorCode in setOf("OUTPUT_CONTINUATION_NO_PROGRESS", "WORKSPACE_EVIDENCE_CONFLICT")
                val executionStatus = if (!blocked && failure.taskOutcome == "PARTIAL") "PARTIAL" else "BLOCKED"
                val rateLimitMessage = failure.rateLimitDeliveryMessage()
                val text = if (rateLimitMessage != null) {
                    val progress = established.ifEmpty { if (restoredResults > 0 || receipts.isNotEmpty()) evidence else "" }
                    rateLimitMessage + progress.takeIf(String::isNotEmpty)?.let { "\n\n$it" }.orEmpty()
                } else {
                    "The GitHub task stopped before a verified final result was available.\n\n" +
                        (if (established.isEmpty()) "$evidence\n\n" else "$established\n\n") +
                        "Status: $executionStatus. Recorded failure: `$reason`. " +
                        stage?.let { "Stopped during `$it`. " }.orEmpty() + nextAction?.let { "Next required step: `$it`. " }.orEmpty() +
                        "Review the failure details before resuming. Completed actions must be reconciled, and uncertain writes must not be repeated."
                }
                emit(
                    AgentRunEvent.Provider(
                        ProviderEvent.TextDelta(
                            text,
                            dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, failure.invocationId)
                        )
                    )
                )
                visible = true
            }
            terminal = true
            emit(AgentRunEvent.Provider(failure))
        }
        upstream.catch { error ->
            // Flow.catch only handles upstream errors. Never recover collector
            // cancellation or process-fatal errors by emitting another response.
            if (error is CancellationException || error !is Exception || error is dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred || error is SavedEvidenceAccessDenied || error is ProviderRecoveryFailure || error is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged || error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) throw error
            val classified = dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification.classify(error)
            if (GatewayOutcomeRecovery.requiresReconciliation(error.message.orEmpty(), classified.errorCode)) {
                emit(AgentRunEvent.Provider(ProviderEvent.Failed(classified.userMessage, errorCode = GatewayOutcomeRecovery.ERROR_CODE)))
            } else {
                val detail = dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partialOutput = visible)
                emit(
                    AgentRunEvent.Provider(
                        if (detail != null) {
                            ProviderEvent.Failed(detail.message, errorCode = detail.code, providerLimit = detail.providerLimit, failureDetails = detail.failureDetails)
                        } else {
                            ProviderEvent.Failed(
                                "The GitHub workflow was interrupted. Saved receipts remain available for inspection.",
                                errorCode = "GITHUB_RUN_INTERRUPTED",
                                failureDetails = IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.AGENT_FAILED_EVENT)
                            )
                        }
                    )
                )
            }
        }.collect { event ->
            if (terminal) {
                if (event is AgentRunEvent.Provider && event.event is ProviderEvent.Usage) emit(event)
                return@collect
            }
            when (event) {
                is AgentRunEvent.ToolResultsRestored -> {
                    restoredResults = event.count
                    emit(event)
                }
                is AgentRunEvent.ToolFinished -> {
                    receipts += event.call.callId
                    dev.chungjungsoo.gptmobile.data.github.GitHubPartialReport.observe(event.call, event.result)?.let { summary ->
                        observations[event.call.callId] = summary
                        while (observations.size > 128) observations.remove(observations.keys.first())
                    }
                    emit(event)
                }
                is AgentRunEvent.Provider -> when (val value = event.event) {
                    is ProviderEvent.Failed -> finish(value)
                    is ProviderEvent.Completed -> if (visible) {
                        terminal = true
                        emit(event)
                    } else {
                        finish(ProviderEvent.Failed("The workflow ended without a visible verified answer.", errorCode = "FINAL_RESPONSE_REQUIRED", transportComplete = true))
                    }
                    is ProviderEvent.TextDelta -> {
                        visible = visible || value.text.isNotBlank()
                        emit(event)
                    }
                    is ProviderEvent.CombinedAnswer -> {
                        visible = visible || value.text.isNotBlank()
                        emit(event)
                    }
                    else -> emit(event)
                }
                else -> emit(event)
            }
        }
        if (!terminal) {
            finish(
                ProviderEvent.Failed(
                    "The workflow ended before a terminal result was delivered.",
                    errorCode = "GITHUB_RUN_INTERRUPTED",
                    failureDetails = buildJsonObject { put("failed_stage", "delivery") }
                )
            )
        }
    }
}

private fun ProviderEvent.Failed.blocksPublication(): Boolean =
    taskOutcome == "CONFIGURATION_INVALIDATED" ||
        errorCode in ToolFailureMetadata.authorityStopCodes ||
        (errorCode == null && message.substringBefore(':') in ToolFailureMetadata.authorityStopCodes) ||
        ToolResultEnvelope.isAuthorizationRejection(errorCode) ||
        (failureDetails?.get("authority_invalidation_code") as? JsonPrimitive)?.content in ToolFailureMetadata.authorityStopCodes

/** The runner has already stopped these cases. A retained-report callback is a new session entry. */
private fun ProviderEvent.Failed.mayRequestRetainedReport(): Boolean =
    providerLimit?.mayWait != true &&
        !blocksPublication() &&
        errorCode !in ToolResultEnvelope.unknownOutcomeCodes &&
        errorCode !in setOf("OUTPUT_CONTINUATION_PENDING_TOOLS", "EXECUTION_OPERATION_UNKNOWN_OUTCOME") &&
        !GatewayOutcomeRecovery.requiresReconciliation(message, errorCode) &&
        listOf("requires_reconciliation", "unknown_outcome", "command_outcome_unknown", "remote_outcome_unknown").none { failureDetails?.get(it) == JsonPrimitive(true) }

private fun ProviderEvent.Failed.rateLimitDeliveryMessage(): String? {
    val classified = ProfileFailureRecord.fromProvider(message, errorCode, failureDetails)
    if (classified.category in setOf(GatewayOutcomeRecovery.ERROR_CODE, "TOOL_OUTCOME_UNKNOWN")) return null
    val status = (failureDetails?.get("http_status") as? kotlinx.serialization.json.JsonPrimitive)?.content
    val rateLimited = classified.category == "RATE_LIMIT" ||
        errorCode in setOf("429", "RATE_LIMIT", "RATE_LIMIT_EXCEEDED", "PROVIDER_RATE_LIMITED") ||
        errorCode in setOf(null, "HTTP_ERROR") &&
        status == "429"
    if (!rateLimited) return null
    val service = if (errorCode?.let(dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy::isRateLimited) == true) "GitHub" else "The AI provider"
    return "$service reached its request limit. Wait for the cooldown, then resume."
}
