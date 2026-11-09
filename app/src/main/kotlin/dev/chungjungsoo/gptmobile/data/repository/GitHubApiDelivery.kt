package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Covers preparation failures as well as the agent loop, while preserving the original failure metadata. */
internal fun Flow<ApiState>.withGitHubApiDelivery(enabled: Boolean, runId: String? = null): Flow<ApiState> {
    if (!enabled) return this
    val upstream = this
    return flow {
        var visible = false
        var failed = false
        var done = false
        suspend fun ensureVisible(error: ApiState.Error? = null) {
            if (visible) return
            if (error?.providerLimit?.knownNotAccepted == true) {
                emit(ApiState.ProgressCheckpoint("The provider limited this request before accepting it. The saved request is being checked for a safe wait and resume.", invocationId = error.invocationId))
                visible = true
                return
            }
            val text = githubFailureDeliveryText(error)
            runId?.let { run ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.traceOutput(run, dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceBoundary.REDUCER, text, invocationId = error?.invocationId, outputOrigin = dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR)
                }
            }
            emit(ApiState.Success(text, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, error?.invocationId)))
            visible = true
        }
        upstream.catch { error ->
            if (error is CancellationException || error !is Exception || error is dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred) throw error
            val classified = ErrorClassification.classify(error)
            val detail = dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partialOutput = visible)
            emit(ApiState.Error(detail?.message ?: classified.userMessage, errorCode = detail?.code ?: classified.errorCode, failureDetails = detail?.failureDetails))
        }.collect { state ->
            if (done) return@collect
            when (state) {
                is ApiState.Success -> if (!failed) {
                    visible = visible || state.textChunk.isNotBlank()
                    emit(state)
                }
                is ApiState.Error -> if (!failed) {
                    ensureVisible(state)
                    failed = true
                    emit(state)
                }
                is ApiState.Done -> {
                    if (!visible) {
                        ensureVisible()
                        if (!failed) emit(ApiState.Error("No verified final answer was delivered.", errorCode = "FINAL_RESPONSE_REQUIRED"))
                    }
                    done = true
                    emit(state)
                }
                else -> emit(state)
            }
        }
        if (!done) {
            ensureVisible()
            if (!failed) emit(ApiState.Error("The workflow ended before its final status was delivered.", errorCode = "GITHUB_RUN_INTERRUPTED"))
            emit(ApiState.Done)
        }
    }
}

internal fun githubFailureDeliveryText(error: ApiState.Error?): String {
    val details = error?.failureDetails
    val unsent = details?.get("dispatched") == kotlinx.serialization.json.JsonPrimitive(false) &&
        details["remote_acceptance"] == kotlinx.serialization.json.JsonPrimitive("NO")
    if (unsent) {
        return requireNotNull(error).message + "\n\nThis request was blocked before execution. Repair the recorded prerequisite, then resume the saved request."
    }
    return "The GitHub request stopped before a verified result was delivered. Review the recorded failure before resuming. Saved action outcomes must be reconciled before any uncertain operation is repeated."
}
