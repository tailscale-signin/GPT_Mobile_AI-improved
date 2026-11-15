package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ToolEventPersistenceFailureTest {
    @Test fun exactConstraintSignaturesAreClassifiedWithoutExportingDatabaseMessages() {
        val cases = mapOf(
            "UNIQUE constraint failed: tool_events.run_id, tool_events.sequence (code 2067 SQLITE_CONSTRAINT_UNIQUE)" to ToolEventPersistenceFailure.Reason.RUN_SEQUENCE_CONFLICT,
            "UNIQUE constraint failed: tool_events.event_id" to ToolEventPersistenceFailure.Reason.EVENT_ID_CONFLICT,
            "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)" to ToolEventPersistenceFailure.Reason.RUN_FOREIGN_KEY,
            "NOT NULL constraint failed: tool_events.call_id" to ToolEventPersistenceFailure.Reason.NOT_NULL
        )
        cases.forEach { (message, reason) ->
            val cause = SQLiteConstraintException(message)
            val failure = ToolEventPersistenceFailure.constraint(cause, 0, 9, 8)
            assertEquals(reason, failure.reason)
            assertSame(cause, failure.cause)
            assertEquals(JsonPrimitive(9), failure.evidence["tool_event_assigned_sequence"])
            assertFalse(failure.evidence.toString().contains(message))
        }
    }

    @Test fun missingOrUnrecognizedConstraintTextRemainsUnknownAndPrivate() {
        listOf(null, "private-query-SECRET", "UNIQUE constraint failed: another_table.run_id, another_table.sequence", "UNIQUE constraint failed: tool_events.run_id, tool_events.sequence private-query-SECRET").forEach { message ->
            val cause = SQLiteConstraintException(message)
            val failure = ToolEventPersistenceFailure.constraint(cause, 0, 0, null)
            assertEquals(ToolEventPersistenceFailure.Reason.UNKNOWN_CONSTRAINT, failure.reason)
            assertSame(cause, failure.cause)
            assertFalse(failure.evidence.toString().contains("SECRET"))
            assertFalse(failure.message.orEmpty().contains("SECRET"))
            assertFalse("tool_event_previous_max_sequence" in failure.evidence)
        }
    }

    @Test fun localWriteEvidenceNeverClaimsGlobalDispatchOrRecoveryAuthority() {
        val failure = ToolEventPersistenceFailure.constraint(SQLiteConstraintException("FOREIGN KEY constraint failed"), 0, 1, 0)
        assertEquals(JsonPrimitive("START"), failure.evidence["tool_event_persistence_operation"])
        assertEquals(JsonPrimitive("INSERT"), failure.evidence["tool_event_persistence_phase"])
        assertEquals(JsonPrimitive(true), failure.evidence["tool_event_write_attempted"])
        assertEquals(7, failure.evidence.size)
        assertTrue(failure.evidence.keys.none { it in setOf("dispatched", "retryable", "remote_request_accepted", "partial_output_preserved", "run_id", "call_id", "tool_arguments") })
    }
}
