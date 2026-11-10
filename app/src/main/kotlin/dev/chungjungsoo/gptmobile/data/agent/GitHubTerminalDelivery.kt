package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** A terminal delivery boundary, after recovery; it never invokes a model or a tool. */
internal fun Flow<AgentRunEvent>.withGitHubTerminalDelivery(enabled: Boolean): Flow<AgentRunEvent> {
    if (!enabled) return this
    val upstream = this
    return flow {
        var visible = false
        var terminal = false
        val receipts = linkedSetOf<String>()
        val observations = linkedMapOf<String, kotlinx.serialization.json.JsonObject>()
        var successfulDispatch = false
        suspend fun finish(failure: ProviderEvent.Failed) {
            if (terminal) return
            // A typed rejection can be saved for the existing provider wait. Do not
            // turn a no-output attempt into an answer before its durable safety check.
            if (!visible && failure.providerLimit?.knownNotAccepted != true) {
                val reason = failure.errorCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "GITHUB_RUN_INTERRUPTED"
                val evidence = if (receipts.isEmpty()) {
                    "No tool result was delivered for this attempt."
                } else {
                    "${receipts.size} tool result(s) were observed; they do not establish that the requested work is complete."
                }
                val established = dev.chungjungsoo.gptmobile.data.github.GitHubPartialReport.render(observations.values)
                val stage = (failure.failureDetails?.get("failed_stage") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
                val nextAction = (failure.failureDetails?.get("next_action") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.matches(Regex("[A-Z_]{1,96}")) }
                val rateLimitMessage = failure.rateLimitDeliveryMessage()
                val text = if (rateLimitMessage != null) {
                    rateLimitMessage + established.takeIf { it.isNotEmpty() }?.let { "\n\n$it" }.orEmpty()
                } else {
                    "The GitHub task stopped before a verified final result was available.\n\n" +
                        (if (established.isEmpty()) "$evidence\n\n" else "$established\n\n") +
                        "Status: ${if (successfulDispatch) "PARTIAL" else "BLOCKED"}. Recorded failure: `$reason`. " +
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
            if (error is CancellationException || error !is Exception || error is dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred || error is SavedEvidenceAccessDenied || error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) throw error
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
                            ProviderEvent.Failed("The GitHub workflow was interrupted. Saved receipts remain available for inspection.", errorCode = "GITHUB_RUN_INTERRUPTED")
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
                is AgentRunEvent.ToolFinished -> {
                    receipts += event.call.callId
                    dev.chungjungsoo.gptmobile.data.github.GitHubPartialReport.observe(event.call, event.result)?.let { summary ->
                        observations[event.call.callId] = summary
                        while (observations.size > 128) observations.remove(observations.keys.first())
                    }
                    successfulDispatch = successfulDispatch || event.result.dispatched && !event.result.isError
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
        if (!terminal) finish(ProviderEvent.Failed("The workflow ended before a terminal result was delivered.", errorCode = "GITHUB_RUN_INTERRUPTED"))
    }
}

private fun ProviderEvent.Failed.rateLimitDeliveryMessage(): String? {
    val classified = ProfileFailureRecord.fromProvider(message, errorCode, failureDetails)
    if (classified.category in setOf(GatewayOutcomeRecovery.ERROR_CODE, "TOOL_OUTCOME_UNKNOWN")) return null
    val status = (failureDetails?.get("http_status") as? kotlinx.serialization.json.JsonPrimitive)?.content
    val rateLimited = classified.category == "RATE_LIMIT" ||
        errorCode in setOf("429", "RATE_LIMIT", "RATE_LIMIT_EXCEEDED", "PROVIDER_RATE_LIMITED") ||
        errorCode in setOf(null, "HTTP_ERROR") && status == "429"
    if (!rateLimited) return null
    val service = if (errorCode?.let(dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy::isRateLimited) == true) "GitHub" else "The AI provider"
    return "$service reached its request limit. Wait for the cooldown, then resume."
}
