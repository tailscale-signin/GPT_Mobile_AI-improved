package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchValidationDiagnosticsTest {
    private val key = "search_validation_field_names"
    private val feature = IssueReportIncidentCapsule.GITHUB_SEARCH_VALIDATION_FEATURE
    private val fieldNames = listOf("fuzzy_threshold", "context_lines", "max_new_blobs", "batch_size")

    private fun capture(fields: Map<String, Any?> = emptyMap(), area: String = "TOOL"): IssueReportContext = requireNotNull(
        IssueReportContext.fromFields(
            "TOOL_RESULT",
            IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "INVALID_ARGUMENTS", mapOf("diagnostic_area" to area) + fields),
            1_800_000_000_000L
        )
    )

    private fun report(context: IssueReportContext) = IssueReport(
        "ce3fdc28-3fe2-48e3-9724-a94a2a513d0b", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "tools", errorCode = "INVALID_ARGUMENTS", context = context
    )

    @Test fun observedSearchValidationNamesPreserveOrderWithoutValuesOrGlobalNameExpansion() {
        for (observed in (1..4).map(fieldNames::take) + listOf(fieldNames.reversed())) {
            for (input in listOf(observed, JsonArray(observed.map(::JsonPrimitive)))) {
                val context = capture(mapOf(key to input, "validation_field_names" to observed, "query" to "private-query", "arguments" to "private-arguments", "error_message" to "private-message"))
                val diagnostic = requireNotNull(context.diagnostics)
                assertEquals(JsonArray(observed.map(::JsonPrimitive)), diagnostic.values[key])
                assertFalse("validation_field_names" in diagnostic.values)
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                assertNull(context.dispatched)
                assertNull(context.remoteAcceptance)
                assertNull(context.retryable)
                val approved = report(context).approvedPayload()
                assertFalse(approved.contains("private"))
                assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
            }
        }
    }

    @Test fun malformedSearchValidationNamesAreDroppedWholeAndRestoredPayloadsAreRejected() {
        val base = requireNotNull(capture().diagnostics)
        val malformed: List<JsonElement> = listOf(
            JsonNull, JsonObject(emptyMap()), JsonPrimitive("context_lines"), JsonPrimitive(true), JsonPrimitive(1),
            JsonArray(emptyList()), JsonArray(listOf(JsonNull)), JsonArray(listOf(JsonPrimitive(true))),
            JsonArray(listOf(JsonPrimitive(1))), JsonArray(listOf(JsonPrimitive("private-query"))),
            JsonArray(listOf(JsonPrimitive("CONTEXT_LINES"))), JsonArray(listOf(JsonPrimitive("query"))),
            JsonArray(listOf(JsonPrimitive("context_lines"), JsonPrimitive("private-query"))),
            JsonArray(List(2) { JsonPrimitive("context_lines") }), JsonArray((fieldNames + "context_lines").map(::JsonPrimitive))
        )
        for (bad in malformed) {
            val observed = requireNotNull(capture(mapOf(key to bad, "tool_budget_calls_used" to 32)).diagnostics)
            assertFalse("$bad", key in observed.values)
            assertFalse(key in observed.omittedFields)
            assertFalse(key in observed.unavailableFields)
            assertEquals(JsonPrimitive(32), observed.values["tool_budget_calls_used"])
            assertFalse(feature in observed.requiredServiceFeatures())
            assertNull("restored $bad", base.copy(values = base.values + (key to bad)).validated())
        }
    }

    @Test fun searchValidationEvidenceRemainsToolSchemaThreeWithOmissionsButNoUnavailableForm() {
        val context = capture()
        val base = requireNotNull(context.diagnostics)
        assertFalse(key in base.values)
        assertFalse(key in base.omittedFields)
        assertFalse(key in base.unavailableFields)
        assertFalse(feature in base.requiredServiceFeatures())
        val omitted = requireNotNull(base.copy(omittedFields = listOf(key)).validated())
        assertTrue(feature in omitted.requiredServiceFeatures())
        val approved = report(context.withDiagnostics(omitted)).approvedPayload()
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
        assertNull(base.copy(unavailableFields = base.unavailableFields + key).validated())
        assertNull(base.copy(omittedFields = listOf(key, key)).validated())
        val measured = JsonArray(listOf(JsonPrimitive("context_lines")))
        val legacy = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", mapOf("diagnostic_area" to "TOOL"), 1000L))
        assertEquals(1, legacy.schemaVersion)
        assertTrue(legacy.isValid())
        assertNull(legacy.copy(values = mapOf(key to measured)).validated())
        assertNull(legacy.copy(omittedFields = listOf(key)).validated())
        val captured = requireNotNull(capture(mapOf(key to measured)).diagnostics)
        assertNull(captured.copy(schemaVersion = 1).validated())
        assertNull(captured.copy(omittedFields = listOf(key)).validated())
        assertNull(captured.copy(captureMode = "NO_CAPTURE").validated())
        for (area in IssueReportDiagnostics.areas - "TOOL") {
            val other = requireNotNull(capture(mapOf(key to measured), area).diagnostics)
            assertFalse(key in other.values)
            assertNull(other.copy(values = other.values + (key to measured)).validated())
            assertNull(other.copy(omittedFields = listOf(key)).validated())
            assertNull(other.copy(unavailableFields = other.unavailableFields + key).validated())
        }
        val disabled = requireNotNull(capture(mapOf(key to measured, "diagnostic_capture_mode" to "NO_CAPTURE")).diagnostics)
        assertTrue(disabled.values.isEmpty())
        assertFalse(feature in disabled.requiredServiceFeatures())
        val selected = requireNotNull(capture(mapOf(key to measured, "diagnostic_capture_mode" to "SELECTED_CAPTURE")).diagnostics)
        assertEquals("SELECTED_CAPTURE", selected.captureMode)
        assertEquals(measured, selected.values[key])
    }

    @Test fun boundedSearchValidationCompactionRetainsCapabilityWithoutRequiringOtherFields() {
        val context = capture(
            mapOf(
                key to fieldNames.reversed(),
                "validation_path_hashes" to List(8) { "a".repeat(64) },
                "unknown_field_hashes" to List(8) { "b".repeat(64) },
                "dependency_call_id_hashes" to List(8) { "c".repeat(64) },
                "exception_type_hashes" to List(8) { "d".repeat(64) },
                "changed_policy_fields" to IssueReportIncidentCapsule.policyFieldNames.toList(),
                "unknown_field_count" to 8, "tool_budget_calls_used" to 32, "tool_budget_calls_limit" to 32
            )
        )
        val diagnostic = requireNotNull(context.diagnostics)
        assertTrue(diagnostic.isValid())
        assertTrue(diagnostic.omittedFields.isNotEmpty())
        assertTrue(key in diagnostic.values || key in diagnostic.omittedFields)
        assertFalse(key in diagnostic.unavailableFields)
        assertTrue(feature in diagnostic.requiredServiceFeatures())
        diagnostic.values[key]?.let { assertEquals(JsonArray(fieldNames.reversed().map(::JsonPrimitive)), it) }
        assertFalse("validation_field_names" in diagnostic.values)
        assertEquals(JsonPrimitive(32), diagnostic.values["tool_budget_calls_used"])
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        val approved = report(context).approvedPayload()
        assertTrue(approved.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
    }
}
