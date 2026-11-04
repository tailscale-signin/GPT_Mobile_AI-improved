package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Covers preparation failures as well as the agent loop, while preserving the original failure metadata. */
internal fun Flow<ApiState>.withGitHubApiDelivery(enabled: Boolean): Flow<ApiState> {
    if (!enabled) return this
    val upstream = this
    return flow {
        var visible = false
        var failed = false
        var done = false
        suspend fun ensureVisible() {
            if (visible) return
            emit(ApiState.Success("The GitHub request stopped before a verified result was delivered. Review the recorded failure before resuming. Saved action outcomes must be reconciled before any uncertain operation is repeated."))
            visible = true
        }
        upstream.catch { error ->
            if (error is CancellationException || error !is Exception || error is dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred) throw error
            val classified = ErrorClassification.classify(error)
            emit(ApiState.Error(classified.userMessage, errorCode = classified.errorCode))
        }.collect { state ->
            if (done) return@collect
            when (state) {
                is ApiState.Success -> if (!failed) {
                    visible = visible || state.textChunk.isNotBlank()
                    emit(state)
                }
                is ApiState.Error -> if (!failed) {
                    ensureVisible()
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
