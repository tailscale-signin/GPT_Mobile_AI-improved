package dev.chungjungsoo.gptmobile.data.context

/** A selected model load is distinct from the dependent engineering or inference request. */
internal class GatewayModelPreparationException(val state: GatewayModelPreparationState) : IllegalStateException("${state.failureCode?.takeIf { it in CODES } ?: "MODEL_PREPARATION_INCOMPLETE"}: ${state.message}") {
    val code: String = state.failureCode?.takeIf { it in CODES } ?: "MODEL_PREPARATION_INCOMPLETE"

    companion object {
        val CODES = setOf("MODEL_PREPARATION_INCOMPLETE", "MODEL_PREPARATION_REJECTED", "MODEL_PREPARATION_STORAGE_UNAVAILABLE", "MODEL_PREPARATION_CONNECTION_FAILED")
    }
}
