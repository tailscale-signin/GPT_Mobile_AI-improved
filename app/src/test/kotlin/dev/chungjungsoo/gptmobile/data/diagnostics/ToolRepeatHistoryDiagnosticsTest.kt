package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRepeatHistoryDiagnosticsTest {
    private val time = 1_800_000_000_000L
    private val family = IssueReportIncidentCapsule.repeatHistoryFields
    private val feature = IssueReportIncidentCapsule.REPEAT_HISTORY_FEATURE
    private val currentCall = "f".repeat(64)
    private fun hash(value: Int) = value.toString(16).padStart(64, '0')

    private fun operands(prior: Int = 1, batch: Int = 1, limit: Int = 2, scope: String = "TOOL_NAME_AND_ARGUMENTS"): Map<String, JsonElement> = mapOf(
        "dependency_repeat_scope" to JsonPrimitive(scope),
        "dependency_repeat_prior_admissions" to JsonPrimitive(prior),
        "dependency_repeat_batch_admissions" to JsonPrimitive(batch),
        "dependency_repeat_used" to JsonPrimitive(prior + batch),
        "dependency_repeat_limit" to JsonPrimitive(limit)
    )

    private fun history(
        prior: List<String> = listOf(hash(1)),
        outcomes: List<String> = List(prior.size) { "DISPATCHED_FAILURE" },
        priorCoverage: String = "COMPLETE",
        batch: List<String> = listOf(hash(20)),
        batchCoverage: String = "COMPLETE"
    ): Map<String, JsonElement> = mapOf(
        "dependency_repeat_prior_call_id_hashes" to JsonArray(prior.map(::JsonPrimitive)),
        "dependency_repeat_prior_outcomes" to JsonArray(outcomes.map(::JsonPrimitive)),
        "dependency_repeat_prior_coverage" to JsonPrimitive(priorCoverage),
        "dependency_repeat_batch_call_id_hashes" to JsonArray(batch.map(::JsonPrimitive)),
        "dependency_repeat_batch_coverage" to JsonPrimitive(batchCoverage)
    )

    private fun capture(fields: Map<String, Any?>, area: String = "TOOL"): IssueReportContext = requireNotNull(
        IssueReportContext.fromFields(
            "TOOL_RESULT",
            IssueReportIncidentCapsule.fields(
                ReportFeature.TOOLS,
                "TOOL_RESULT",
                "TOOL_FAILURE",
                mapOf("diagnostic_area" to area, "failed_stage" to "tool_precheck", "dispatched" to false, "call_id_hash" to currentCall) + fields
            ),
            time
        )
    )

    private fun report(context: IssueReportContext) = IssueReport(
        "8318933a-3c96-4b75-af57-4973a70641e7", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "tools", errorCode = "TOOL_FAILURE", context = context
    )

    @Test fun coherentHistoryPreservesObservedOutcomesCoverageAndCurrentCallScope() {
        val samples = listOf(
            operands(0, 0, 0) to history(prior = emptyList(), batch = emptyList()),
            operands(3, 0, 3) to history(prior = listOf(hash(1)), priorCoverage = "PARTIAL", batch = emptyList()),
            operands(3, 2, 3) to history(prior = emptyList(), priorCoverage = "UNAVAILABLE", batch = listOf(hash(20)), batchCoverage = "PARTIAL"),
            operands(3, 2, 3) to history(prior = listOf(hash(1)), priorCoverage = "PARTIAL", batch = emptyList(), batchCoverage = "UNAVAILABLE"),
            operands(8, 8, 3) to history(prior = (1..8).map(::hash), batch = (20..27).map(::hash)),
            operands(16_000_000, 0, 16_000_000) to history(prior = emptyList(), priorCoverage = "UNAVAILABLE", batch = emptyList())
        ) + listOf("UNOBSERVED", "DISPATCHED_SUCCESS", "DISPATCHED_FAILURE", "NOT_DISPATCHED", "SHARED_RESULT", "UNKNOWN_OUTCOME").map {
            operands() to history(outcomes = listOf(it))
        }
        for (scope in listOf("TOOL_NAME", "TOOL_NAME_AND_ARGUMENTS")) {
            for ((counts, history) in samples) {
                val observed = counts + ("dependency_repeat_scope" to JsonPrimitive(scope)) + history
                val context = capture(observed + mapOf("url" to "private-url", "repeat_key" to "private-key", "prior_call_ids" to listOf("private-call")))
                val diagnostic = requireNotNull(context.diagnostics)
                assertEquals(observed, diagnostic.values.filterKeys { it in family || it in IssueReportIncidentCapsule.repeatAdmissionFields })
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                assertEquals(false, context.dispatched)
                assertNull(context.remoteAcceptance)
                assertNull(context.requiresReconciliation)
                val approved = report(context).approvedPayload()
                assertFalse(approved.contains("private"))
                assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
                assertTrue(approved.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
            }
        }
    }

    @Test fun missingOrMalformedHistoryIsDiscardedTogetherWithoutDroppingAdmissionOperands() {
        val original = operands() + history()
        val base = requireNotNull(capture(operands()).diagnostics)
        val malformed = mutableListOf<Map<String, JsonElement>>()
        family.forEach { malformed += original - it }
        for (key in family) {
            val bad = listOf(JsonNull, JsonObject(emptyMap()), JsonPrimitive(true), JsonPrimitive("private-value"), JsonPrimitive(3)) +
                if (key in IssueReportIncidentCapsule.repeatHistoryListFields) {
                    listOf(JsonArray(listOf(JsonPrimitive("PRIVATE"))), JsonArray(listOf(JsonNull)), JsonArray(List(9) { JsonPrimitive(hash(it + 1)) }))
                } else {
                    listOf(JsonArray(emptyList()))
                }
            bad.forEach { malformed += original + (key to it) }
        }
        for (fields in malformed) {
            val context = capture(fields)
            val diagnostic = requireNotNull(context.diagnostics)
            assertTrue(diagnostic.values.keys.intersect(family).isEmpty())
            assertEquals(operands(), diagnostic.values.filterKeys { it in IssueReportIncidentCapsule.repeatAdmissionFields })
            assertTrue(diagnostic.omittedFields.intersect(family).isEmpty())
            assertFalse(feature in diagnostic.requiredServiceFeatures())
            assertNull(base.copy(values = base.values + fields).validated())
            assertFalse(report(context).approvedPayload().contains("private"))
        }
    }

    @Test fun historyRequiresCoherentOperandsMatchingCoverageAndDistinctNonSelfLinks() {
        val original = operands() + history()
        val malformed = mutableListOf<Map<String, JsonElement>>()
        IssueReportIncidentCapsule.repeatAdmissionFields.forEach { malformed += original - it }
        for ((key, value) in listOf(
            "dependency_repeat_scope" to JsonPrimitive("UNKNOWN"),
            "dependency_repeat_prior_admissions" to JsonPrimitive(-1),
            "dependency_repeat_batch_admissions" to JsonPrimitive(16_000_001),
            "dependency_repeat_used" to JsonPrimitive(3),
            "dependency_repeat_limit" to JsonPrimitive(3),
            "dependency_repeat_prior_admissions" to JsonPrimitive("1"),
            "dependency_repeat_prior_admissions" to JsonPrimitive(1.0),
            "dependency_repeat_prior_admissions" to JsonPrimitive(true)
        )) {
            malformed += original + (key to value)
        }
        malformed += operands(2, 1, 2) + history(prior = listOf(hash(1), hash(1)))
        malformed += operands() + history(prior = listOf(hash(20)))
        malformed += operands() + history(prior = listOf(currentCall))
        malformed += operands() + history(batch = listOf(currentCall))
        malformed += operands() + history(prior = listOf("A".repeat(64)))
        malformed += operands() + history(outcomes = emptyList())
        malformed += operands() + history(outcomes = listOf("UNOBSERVED", "UNKNOWN_OUTCOME"))
        malformed += operands() + history(priorCoverage = "PARTIAL")
        malformed += operands() + history(batchCoverage = "PARTIAL")
        malformed += operands() + history(priorCoverage = "UNAVAILABLE")
        malformed += operands() + history(batchCoverage = "UNAVAILABLE")
        malformed += operands(0, 0, 0) + history(prior = emptyList(), priorCoverage = "UNAVAILABLE", batch = emptyList())
        malformed += operands(0, 0, 0) + history(prior = emptyList(), batch = emptyList(), batchCoverage = "PARTIAL")
        malformed += operands(2, 1, 2) + history()
        val base = requireNotNull(capture(operands()).diagnostics)
        for (fields in malformed) {
            val diagnostic = requireNotNull(capture(fields).diagnostics)
            assertTrue(diagnostic.values.keys.intersect(family).isEmpty())
            assertNull(base.copy(values = (base.values - IssueReportIncidentCapsule.repeatAdmissionFields) + fields).validated())
        }
        // Existing scalar evidence remains valid independently, including a
        // partial or inconsistent legacy snapshot. History cannot bless it.
        val legacy = operands() + ("dependency_repeat_used" to JsonPrimitive(10))
        assertEquals(legacy, requireNotNull(capture(legacy).diagnostics).values.filterKeys { it in IssueReportIncidentCapsule.repeatAdmissionFields })
        for (key in IssueReportIncidentCapsule.repeatAdmissionFields) {
            assertTrue(requireNotNull(capture(mapOf(key to operands().getValue(key))).diagnostics).isValid())
        }
    }

    @Test fun restoredTopLevelIdentityRejectsSelfLinksWithoutInventingAbsentHashes() {
        val observed = capture(operands() + history() + ("call_id" to "private-current-call"))
        val actualCurrent = requireNotNull(observed.callIdHash)
        val withoutDiagnosticIdentity = requireNotNull(
            IssueReportDiagnostics.fromFields("TOOL_RESULT", mapOf("diagnostic_area" to "TOOL") + operands() + history(), time)
        )
        assertNull(withoutDiagnosticIdentity.values["call_id_hash"])
        val valid = report(observed.withDiagnostics(withoutDiagnosticIdentity)).approvedPayload()
        assertEquals(valid, IssueReport.json.decodeFromString(IssueReport.serializer(), valid).approvedPayload())
        for (key in listOf("dependency_repeat_prior_call_id_hashes", "dependency_repeat_batch_call_id_hashes")) {
            val selfLinked = withoutDiagnosticIdentity.copy(values = withoutDiagnosticIdentity.values + (key to JsonArray(listOf(JsonPrimitive(actualCurrent)))))
            assertTrue(selfLinked.isValid()) // The standalone capture has no current identity.
            val rejected = report(observed.withDiagnostics(selfLinked))
            val original = IssueReport.json.encodeToString(IssueReport.serializer(), rejected)
            assertThrows(IllegalArgumentException::class.java) { rejected.approvedPayload() }
            assertEquals(original, IssueReport.json.encodeToString(IssueReport.serializer(), rejected))
            val absentIdentity = capture(operands() + history()).withDiagnostics(selfLinked)
            assertNull(absentIdentity.callIdHash)
            assertNull(requireNotNull(absentIdentity.diagnostics).values["call_id_hash"])
            assertTrue(report(absentIdentity).approvedPayload().encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        }
    }

    @Test fun absentHistoryMarkersRemainUnknownAndRequireSupportWithoutCreatingCoverage() {
        val context = capture(operands())
        val base = requireNotNull(context.diagnostics)
        assertFalse(feature in base.requiredServiceFeatures())
        for (key in family) {
            for (diagnostic in listOf(base.copy(omittedFields = listOf(key)), base.copy(unavailableFields = base.unavailableFields + key))) {
                assertTrue(diagnostic.isValid())
                assertTrue(diagnostic.values.keys.intersect(family).isEmpty())
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                val approved = report(context.withDiagnostics(diagnostic)).approvedPayload()
                val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), approved)
                assertEquals(diagnostic, restored.context?.diagnostics)
                assertEquals(approved, restored.approvedPayload())
            }
        }
        val complete = requireNotNull(capture(operands() + history()).diagnostics)
        assertNull(complete.copy(omittedFields = family.toList()).validated())
        assertNull(complete.copy(unavailableFields = complete.unavailableFields + family).validated())
    }

    @Test fun historyRemainsToolOnlyAndRespectsSchemaAndCaptureMode() {
        val fields = operands() + history()
        for (area in IssueReportDiagnostics.areas - "TOOL") {
            val other = requireNotNull(capture(fields, area).diagnostics)
            assertTrue(other.values.keys.intersect(family).isEmpty())
            assertNull(other.copy(values = other.values + fields).validated())
            for (key in family) {
                assertNull(other.copy(omittedFields = listOf(key)).validated())
                assertNull(other.copy(unavailableFields = other.unavailableFields + key).validated())
            }
        }
        val complete = requireNotNull(capture(fields).diagnostics)
        assertNull(complete.copy(schemaVersion = 1).validated())
        assertNull(complete.copy(captureMode = "NO_CAPTURE").validated())
        val disabled = requireNotNull(capture(fields + ("diagnostic_capture_mode" to "NO_CAPTURE")).diagnostics)
        assertTrue(disabled.values.isEmpty())
        assertFalse(feature in disabled.requiredServiceFeatures())
        val selected = requireNotNull(capture(fields + ("diagnostic_capture_mode" to "SELECTED_CAPTURE")).diagnostics)
        assertEquals("SELECTED_CAPTURE", selected.captureMode)
        assertEquals(family, selected.values.keys.intersect(family))
    }

    @Test fun compactionDropsTheWholeHistoryAndPreservesIndependentAdmissionOperands() {
        val counts = operands(8, 8, 3)
        val context = capture(
            counts + history(prior = (1..8).map(::hash), batch = (20..27).map(::hash)) + mapOf(
                "validation_path_hashes" to List(8) { hash(100 + it) },
                "unknown_field_hashes" to List(8) { hash(200 + it) },
                "unknown_field_count" to 8,
                "exception_type_hashes" to List(8) { hash(300 + it) },
                "changed_policy_fields" to IssueReportIncidentCapsule.policyFieldNames.toList()
            )
        )
        val diagnostic = requireNotNull(context.diagnostics)
        assertTrue(diagnostic.values.keys.intersect(family).isEmpty())
        assertTrue(diagnostic.omittedFields.containsAll(family))
        assertEquals(counts, diagnostic.values.filterKeys { it in IssueReportIncidentCapsule.repeatAdmissionFields })
        assertTrue(feature in diagnostic.requiredServiceFeatures())
        assertTrue(diagnostic.isValid())
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        val approved = report(context).approvedPayload()
        assertTrue(approved.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
    }
}
