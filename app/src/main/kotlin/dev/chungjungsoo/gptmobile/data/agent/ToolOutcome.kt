package dev.chungjungsoo.gptmobile.data.agent

/** Stable semantic status shared by orchestration, diagnostics, and export. */
enum class ToolOutcome {
    SUCCESS,
    PARTIAL,
    EMPTY,
    BLOCKED,
    AUTH_FAILED,
    RATE_LIMITED,
    INVALID_RESPONSE,
    BUDGET_EXHAUSTED,
    CIRCUIT_OPEN,
    FAILED
}
