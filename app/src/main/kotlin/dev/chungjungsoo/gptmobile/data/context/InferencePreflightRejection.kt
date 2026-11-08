package dev.chungjungsoo.gptmobile.data.context

/** Fixed local categories, recorded only at a boundary that precedes inference dispatch. */
internal enum class InferencePreflightReason {
    REASONING_OFF_UNSUPPORTED,
    REASONING_ON_UNSUPPORTED,
    GATEWAY_CAPABILITY_UNAVAILABLE
}

internal data class InferencePreflightRejection(val reason: InferencePreflightReason) {
    val phase: String get() = "LOCAL_INFERENCE_ADMISSION"
    val dispatch: String get() = "NOT_DISPATCHED"
}

class ReasoningAdmissionException(val requested: ReasoningMode) :
    IllegalStateException(
        "REASONING_${requested}_UNSUPPORTED: this route has no verified template control. Choose Auto or a qualified model; no inference was sent."
    ) {
    init {
        require(requested != ReasoningMode.AUTO)
    }
}

class ReasoningCapabilityUnavailableException(val code: String) :
    IllegalStateException(
        "$code: The selected writer's exact model and reasoning controls are not qualified. Recheck capabilities before collecting contributions."
    ) {
    init {
        require(code in setOf("REASONING_CAPABILITY_UNVERIFIED", "REASONING_CAPABILITY_UNAVAILABLE", "REASONING_CAPABILITY_TIMEOUT"))
    }
}
