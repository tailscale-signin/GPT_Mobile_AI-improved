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
        var successfulDispatch = false
        suspend fun finish(failure: ProviderEvent.Failed) {
            if (terminal) return
            if (!visible) {
                val reason = failure.errorCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "GITHUB_RUN_INTERRUPTED"
                val evidence = if (receipts.isEmpty()) {
                    "No tool result was delivered for this attempt."
                } else {
                    "${receipts.size} tool result(s) were observed; they do not establish that the requested work is complete."
                }
                emit(
                    AgentRunEvent.Provider(
                        ProviderEvent.TextDelta(
                            "The GitHub task stopped before a verified final result was available.\n\n" +
                                "$evidence\n\nStatus: ${if (successfulDispatch) "PARTIAL" else "BLOCKED"}. Recorded failure: `$reason`. " +
                                "Review the failure details before resuming. Completed actions must be reconciled, and uncertain writes must not be repeated."
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
                emit(AgentRunEvent.Provider(ProviderEvent.Failed("The GitHub workflow was interrupted. Saved receipts remain available for inspection.", errorCode = "GITHUB_RUN_INTERRUPTED")))
            }
        }.collect { event ->
            if (terminal) {
                if (event is AgentRunEvent.Provider && event.event is ProviderEvent.Usage) emit(event)
                return@collect
            }
            when (event) {
                is AgentRunEvent.ToolFinished -> {
                    receipts += event.call.callId
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
