package dev.chungjungsoo.gptmobile.data.research.delivery

/** A nonempty rejected correction is not an empty report or an accepted revision. */
internal sealed interface ResearchRepairResult {
    data class Applied(val text: String) : ResearchRepairResult
    data class Rejected(val reason: Reason) : ResearchRepairResult

    enum class Reason {
        EMPTY_RESPONSE,
        RESPONSE_TOO_LARGE,
        INVALID_JSON,
        INVALID_SCHEMA,
        UNKNOWN_BLOCK,
        PRESERVED_BLOCK,
        DUPLICATE_BLOCK,
        HASH_MISMATCH,
        INVALID_REPLACEMENT,
        REPORT_TOO_LARGE,
        REQUEST_FAILED,
        INCOMPLETE_RESPONSE,
        TIMEOUT,
        UNEXPECTED_TOOL_CALL,
        CANCELLED;

        val issueCode: String get() = "REPAIR_$name"

        val description: String get() = when (this) {
            EMPTY_RESPONSE -> "The correction returned no text."
            RESPONSE_TOO_LARGE, REPORT_TOO_LARGE -> "The correction exceeded the report limit."
            REQUEST_FAILED -> "The correction request failed."
            INCOMPLETE_RESPONSE -> "The correction did not finish."
            TIMEOUT -> "The correction timed out."
            CANCELLED -> "The correction was cancelled."
            INVALID_REPLACEMENT -> "The correction failed its evidence checks."
            else -> "The correction could not be safely applied."
        }
    }
}
