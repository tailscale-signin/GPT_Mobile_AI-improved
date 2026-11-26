package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An explicit model-repair action can wake only its still-owned selected model. */
internal suspend fun recheckProfileModel(
    request: ReasoningRecoveryRequest,
    validateSelection: suspend () -> Unit,
    check: suspend () -> ReasoningCapabilityObservation?,
    prepare: (suspend () -> Unit)?
): ReasoningCapabilityObservation? {
    suspend fun validate() {
        currentCoroutineContext().ensureActive()
        validateSelection()
    }
    validate()
    var result = check()
    validate()
    if (request.requireRouteReady && result?.safeReasonCode in setOf("SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_LOADING") && prepare != null) {
        prepare()
        validate()
        result = check()
        validate()
    }
    return result
}
