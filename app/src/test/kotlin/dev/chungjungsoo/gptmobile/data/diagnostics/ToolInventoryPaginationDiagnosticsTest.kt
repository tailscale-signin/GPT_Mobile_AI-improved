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

class ToolInventoryPaginationDiagnosticsTest {
    private val reason = "inventory_pagination_reason"
    private val offered = "inventory_restart_offered"
    private val fields = setOf(reason, offered)
    private val feature = IssueReportIncidentCapsule.TOOL_INVENTORY_PAGINATION_FEATURE

    private fun capture(values: Map<String, Any?> = emptyMap(), area: String = "TOOL"): IssueReportContext = requireNotNull(
        IssueReportContext.fromFields(
            "TOOL_RESULT",
            IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "TOOL_FAILURE", mapOf("diagnostic_area" to area) + values),
            1_800_000_000_000L
        )
    )

    private fun report(context: IssueReportContext) = IssueReport(
        "8318933a-3c96-4b75-af57-4973a70641e7", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "tools", errorCode = "TOOL_FAILURE", context = context
    )

    @Test fun paginationReasonAndRestartOfferRemainIndependentObservedValues() {
        val samples = listOf(
            mapOf(reason to JsonPrimitive("MISSING_VERSION")),
            mapOf(reason to JsonPrimitive("VERSION_MISMATCH")),
            mapOf(offered to JsonPrimitive(false)),
            mapOf(offered to JsonPrimitive(true))
        ) + listOf("MISSING_VERSION", "VERSION_MISMATCH").flatMap { code ->
            listOf(false, true).map { flag -> mapOf(reason to JsonPrimitive(code), offered to JsonPrimitive(flag)) }
        }
        for (observed in samples) {
            val context = capture(observed + mapOf("inventory_version" to "private-version", "cursor" to "private-cursor", "error_message" to "private-message"))
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(observed, diagnostic.values.filterKeys { it in fields })
            assertTrue(feature in diagnostic.requiredServiceFeatures())
            assertNull(context.dispatched)
            assertNull(context.remoteAcceptance)
            assertNull(context.retryable)
            assertNull(context.recoveryAction)
            val approved = report(context).approvedPayload()
            assertFalse(approved.contains("private"))
            assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
        }
        val primitive = requireNotNull(capture(mapOf(offered to false)).diagnostics)
        assertEquals(JsonPrimitive(false), primitive.values[offered])
        val absent = requireNotNull(capture().diagnostics)
        assertTrue(absent.values.keys.intersect(fields).isEmpty())
        assertTrue(absent.unavailableFields.intersect(fields).isEmpty())
        assertFalse(feature in absent.requiredServiceFeatures())
    }

    @Test fun malformedPaginationFieldsAreNotCoercedAndDoNotEraseQualifiedNeighbors() {
        val base = requireNotNull(capture().diagnostics)
        val invalid: Map<String, List<JsonElement>> = mapOf(
            reason to listOf(JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()), JsonPrimitive(true), JsonPrimitive(1), JsonPrimitive("private-reason"), JsonPrimitive("missing_version")),
            offered to listOf(JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()), JsonPrimitive("true"), JsonPrimitive("false"), JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("private-offer"))
        )
        for ((key, malformed) in invalid) {
            val neighbor = if (key == reason) mapOf(offered to JsonPrimitive(false)) else mapOf(reason to JsonPrimitive("VERSION_MISMATCH"))
            for (bad in malformed) {
                val diagnostic = requireNotNull(capture(neighbor + (key to bad)).diagnostics)
                assertEquals(neighbor, diagnostic.values.filterKeys { it in fields })
                assertFalse(key in diagnostic.omittedFields)
                assertFalse(key in diagnostic.unavailableFields)
                assertNull("restored $key=$bad", base.copy(values = base.values + neighbor + (key to bad)).validated())
            }
        }
    }

    @Test fun paginationEvidenceIsToolSchemaThreeWithOmissionsButNoUnavailableForm() {
        val context = capture()
        val base = requireNotNull(context.diagnostics)
        val legacy = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", mapOf("diagnostic_area" to "TOOL"), 1000L))
        assertEquals(1, legacy.schemaVersion)
        assertTrue(legacy.isValid())
        val samples = mapOf(reason to JsonPrimitive("MISSING_VERSION"), offered to JsonPrimitive(false))
        for ((key, value) in samples) {
            val neighbor = samples - key
            val omitted = requireNotNull(base.copy(values = base.values + neighbor, omittedFields = listOf(key)).validated())
            assertEquals(neighbor, omitted.values.filterKeys { it in fields })
            assertTrue(feature in omitted.requiredServiceFeatures())
            val approved = report(context.withDiagnostics(omitted)).approvedPayload()
            assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
            assertNull(base.copy(unavailableFields = base.unavailableFields + key).validated())
            assertNull(base.copy(omittedFields = listOf(key, key)).validated())
            assertNull(legacy.copy(values = mapOf(key to value)).validated())
            assertNull(legacy.copy(omittedFields = listOf(key)).validated())
            for (area in IssueReportDiagnostics.areas - "TOOL") {
                val other = requireNotNull(capture(mapOf(key to value), area).diagnostics)
                assertFalse(key in other.values)
                assertNull(other.copy(values = other.values + (key to value)).validated())
                assertNull(other.copy(omittedFields = listOf(key)).validated())
                assertNull(other.copy(unavailableFields = other.unavailableFields + key).validated())
            }
        }
        val complete = requireNotNull(capture(samples).diagnostics)
        assertNull(complete.copy(captureMode = "NO_CAPTURE").validated())
        val disabled = requireNotNull(capture(samples + ("diagnostic_capture_mode" to "NO_CAPTURE")).diagnostics)
        assertTrue(disabled.values.isEmpty())
        assertFalse(feature in disabled.requiredServiceFeatures())
        val selected = requireNotNull(capture(samples + ("diagnostic_capture_mode" to "SELECTED_CAPTURE")).diagnostics)
        assertEquals("SELECTED_CAPTURE", selected.captureMode)
        assertEquals(samples, selected.values.filterKeys { it in fields })
    }

    @Test fun boundedPaginationCompactionPreservesObservedFalseAndCapabilityOrExplicitOmission() {
        val observed = mapOf(reason to JsonPrimitive("VERSION_MISMATCH"), offered to JsonPrimitive(false))
        val context = capture(
            observed + mapOf(
                "validation_path_hashes" to List(8) { "a".repeat(64) },
                "unknown_field_hashes" to List(8) { "b".repeat(64) },
                "dependency_call_id_hashes" to List(8) { "c".repeat(64) },
                "exception_type_hashes" to List(8) { "d".repeat(64) },
                "changed_policy_fields" to IssueReportIncidentCapsule.policyFieldNames.toList(),
                "tool_budget_calls_used" to 3,
                "tool_budget_calls_limit" to 3
            )
        )
        val diagnostic = requireNotNull(context.diagnostics)
        assertTrue(diagnostic.isValid())
        assertTrue(diagnostic.omittedFields.isNotEmpty())
        assertEquals(fields, (diagnostic.values.keys + diagnostic.omittedFields).intersect(fields))
        assertTrue(feature in diagnostic.requiredServiceFeatures())
        for ((key, value) in observed) diagnostic.values[key]?.let { assertEquals(value, it) }
        assertEquals(JsonPrimitive(3), diagnostic.values["tool_budget_calls_used"])
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        val approved = report(context).approvedPayload()
        assertTrue(approved.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
    }
}
