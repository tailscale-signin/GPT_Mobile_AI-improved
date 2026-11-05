package dev.chungjungsoo.gptmobile.data.agent.tool

/** An interrupted child may have completed writes; another helper cannot safely replay it. */
internal fun isUncertainDelegateOutcome(message: String): Boolean =
    dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery.requiresReconciliation(message) ||
        dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.unknownOutcomeCodes.any { message.contains(it, ignoreCase = true) }

/** Provider-specific completion-limit messages that should end an identical retry loop. */
internal fun isProviderOutputLimitFailure(message: String): Boolean {
    val normalized = message.lowercase()
    return listOf(
        "reached its output limit",
        "reached the model output limit",
        "output_limit:",
        "completion_repair_budget_exhausted",
        "exhausted the output allowance",
        "maximum output tokens",
        "maximum completion tokens",
        "finish_reason=length",
        "finish reason: length",
        "finish reason length"
    ).any(normalized::contains)
}

/** These pre-dispatch failures cannot improve by resending identical configuration. */
internal fun isDeterministicDelegateConfigurationFailure(message: String): Boolean = listOf(
    "REASONING_OFF_UNSUPPORTED",
    "REASONING_ON_UNSUPPORTED",
    "MODEL_CONFIGURATION",
    "Serializer for subclass",
    "Serializer for class",
    "LOCAL_CONTEXT_EXHAUSTED",
    "LOCAL_CONTEXT_UNVERIFIED",
    "NATIVE_SESSION_QUARANTINED",
    "NATIVE_ARTIFACT_QUARANTINED",
    "interrupted inside native code"
).any { message.contains(it, ignoreCase = true) }
