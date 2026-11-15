package dev.chungjungsoo.gptmobile.data.database

import android.database.sqlite.SQLiteConstraintException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Facts from this local write only. They say nothing about earlier tool or provider dispatch. */
class ToolEventPersistenceFailure private constructor(
    val reason: Reason,
    val requestedSequence: Int,
    val assignedSequence: Int?,
    val previousMaximum: Int?,
    cause: Throwable?
) : IllegalStateException("TOOL_EVENT_PERSISTENCE_FAILED: ${reason.name}", cause) {
    enum class Reason { RUN_SEQUENCE_CONFLICT, EVENT_ID_CONFLICT, RUN_FOREIGN_KEY, NOT_NULL, UNKNOWN_CONSTRAINT, SEQUENCE_EXHAUSTED }

    val evidence: JsonObject = buildJsonObject {
        put("tool_event_persistence_operation", "START")
        put("tool_event_persistence_phase", if (assignedSequence == null) "ALLOCATE_SEQUENCE" else "INSERT")
        put("tool_event_persistence_failure", reason.name)
        put("tool_event_requested_sequence", requestedSequence)
        assignedSequence?.let { put("tool_event_assigned_sequence", it) }
        previousMaximum?.let { put("tool_event_previous_max_sequence", it) }
        put("tool_event_write_attempted", assignedSequence != null)
    }

    companion object {
        fun sequenceExhausted(requestedSequence: Int, previousMaximum: Int?): ToolEventPersistenceFailure =
            ToolEventPersistenceFailure(Reason.SEQUENCE_EXHAUSTED, requestedSequence, null, previousMaximum, null)

        fun constraint(failure: SQLiteConstraintException, requestedSequence: Int, assignedSequence: Int, previousMaximum: Int?): ToolEventPersistenceFailure {
            // Never retain the SQLite message, SQL, arguments or identifiers in public fields.
            // Only the exact known table/column signatures can identify a specific constraint.
            val signature = failure.message.orEmpty().substringBefore(" (code ").trim()
            val reason = when (signature) {
                "UNIQUE constraint failed: tool_events.run_id, tool_events.sequence" -> Reason.RUN_SEQUENCE_CONFLICT
                "UNIQUE constraint failed: tool_events.event_id" -> Reason.EVENT_ID_CONFLICT
                "FOREIGN KEY constraint failed" -> Reason.RUN_FOREIGN_KEY
                else -> if (signature.startsWith("NOT NULL constraint failed: tool_events.")) Reason.NOT_NULL else Reason.UNKNOWN_CONSTRAINT
            }
            return ToolEventPersistenceFailure(reason, requestedSequence, assignedSequence, previousMaximum, failure)
        }
    }
}
