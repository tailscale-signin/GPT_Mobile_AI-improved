package dev.chungjungsoo.gptmobile.data.context

/** Fixed local categories, recorded only at a boundary that precedes inference dispatch. */
internal enum class InferencePreflightReason {
    REASONING_OFF_UNSUPPORTED,
    REASONING_ON_UNSUPPORTED,
    REASONING_CAPABILITY_UNVERIFIED,
    REASONING_CAPABILITY_UNAVAILABLE,
    REASONING_CAPABILITY_TIMEOUT,
    GATEWAY_CAPABILITY_UNAVAILABLE,
    GATEWAY_ISOLATION_UNAVAILABLE
}

internal data class InferencePreflightRejection(val reason: InferencePreflightReason) {
    val phase: String get() = "LOCAL_INFERENCE_ADMISSION"
    val dispatch: String get() = "NOT_DISPATCHED"
    val failedStage: String get() = if (reason.name.startsWith("GATEWAY_")) "connection_preflight" else "provider_admission"
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
