package dev.chungjungsoo.gptmobile.data.context

/** Fixed local categories, recorded only at a boundary that precedes inference dispatch. */
internal enum class InferencePreflightReason {
    REASONING_OFF_UNSUPPORTED,
    REASONING_ON_UNSUPPORTED,
    REASONING_CAPABILITY_UNVERIFIED,
    REASONING_CAPABILITY_UNAVAILABLE,
    REASONING_CAPABILITY_TIMEOUT,
    SELECTED_MODEL_NOT_LOADED,
    SELECTED_MODEL_LOADING,
    SELECTED_MODEL_NOT_AVAILABLE,
    GATEWAY_CAPABILITY_UNAVAILABLE,
    GATEWAY_ISOLATION_UNAVAILABLE
}

internal data class InferencePreflightRejection(val reason: InferencePreflightReason) {
    val phase: String get() = "LOCAL_INFERENCE_ADMISSION"
    val dispatch: String get() = "NOT_DISPATCHED"
    val failedStage: String get() = if (reason.name.startsWith("GATEWAY_")) "connection_preflight" else "provider_admission"
}

internal fun String?.isSelectedModelBlocker(): Boolean =
    this == "SELECTED_MODEL_NOT_LOADED" || this == "SELECTED_MODEL_LOADING" || this == "SELECTED_MODEL_NOT_AVAILABLE"

internal class SelectedModelUnavailableException(
    val reason: InferencePreflightReason,
    val observation: GatewayModelCapabilityObservation
) : IllegalStateException("${reason.name}: ${observation.blockingSummary()} This attempt stopped before inference.") {
    init {
        require(reason.name.isSelectedModelBlocker())
    }
}

class ReasoningAdmissionException(val requested: ReasoningMode) :
    IllegalStateException(
        "REASONING_${requested}_UNSUPPORTED: this route has no verified template control. Choose Auto or a qualified model; no inference was sent."
    ) {
    init {
        require(requested != ReasoningMode.AUTO)
    }
}

class ReasoningCapabilityUnavailableException internal constructor(val code: String, internal val gatewayFailure: GatewayModelCapabilityObservation? = null) :
    IllegalStateException(
        "$code: ${gatewayFailure?.blockingSummary() ?: "The selected model's exact route and reasoning controls are not qualified. Recheck its connection and capabilities."} The saved reasoning setting is unchanged and no inference was sent."
    ) {
    init {
        require(code in setOf("REASONING_CAPABILITY_UNVERIFIED", "REASONING_CAPABILITY_UNAVAILABLE", "REASONING_CAPABILITY_TIMEOUT"))
    }
}
