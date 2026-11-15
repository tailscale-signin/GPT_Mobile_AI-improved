package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTimelineDiagnosticsTest {
    private val legacy = mapOf<String, Any?>("composer_focus_transitions" to 12, "composer_ime_transitions" to 12, "composer_focused" to false, "composer_ime_visible" to false)

    private fun timeline(events: List<String> = List(8) { "RELEASE_LIFECYCLE_STOP" }): Map<String, Any?> = buildMap {
        putAll(legacy)
        put("composer_generation", 16_000_000)
        put("composer_events_dropped", 16_000_000)
        put("composer_enabled", true)
        put("composer_window_focused", false)
        put("composer_lifecycle", "RESUMED")
        put("composer_input_mode", "KEYBOARD")
        events.forEachIndexed { index, event ->
            put("composer_event_${index + 1}", event)
            put("composer_event_${index + 1}_age_ms", if (index == events.lastIndex) 0 else 600_000 - index)
        }
    }

    private fun capture(fields: Map<String, Any?>, area: String = "APP") = requireNotNull(IssueReportDiagnostics.fromFields("MANUAL_REPORT", fields + mapOf("diagnostic_area" to area, "diagnostic_capture_mode" to "SELECTED_CAPTURE"), 1000))

    @Test fun completeEightSlotSnapshotRoundTripsAndDistinguishesOppositeObservedSequences() {
        val full = capture(timeline())
        assertEquals(26, full.values.size)
        assertEquals(22, IssueReportDiagnostics.composerTimelineFields.size)
        assertEquals(2, full.requiredReporterVersion())
        assertTrue("COMPOSER_IME_EVIDENCE_V1" in full.requiredServiceFeatures())
        assertEquals(full, IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), full)))
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), full).encodeToByteArray().size <= 3072)
        assertTrue(full.omittedFields.isEmpty())
        assertTrue(full.unavailableFields.none { it in IssueReportDiagnostics.composerTimelineFields })
        val requested = capture(timeline(listOf("EDITOR_FOCUSED", "RELEASE_BACKGROUND_TAP", "EDITOR_UNFOCUSED")))
        val observed = capture(timeline(listOf("EDITOR_FOCUSED", "IME_HIDDEN", "WINDOW_UNFOCUSED")))
        assertEquals(requested.values.filterKeys { it in legacy }, observed.values.filterKeys { it in legacy })
        assertNotEquals(requested.values, observed.values)
        val old = capture(legacy)
        assertEquals(4, old.values.size)
        assertEquals(0, old.requiredReporterVersion())
        assertEquals(old, IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), old)))
    }

    @Test fun strictWireRejectsMalformedTypesBoundsEnumsPairsHolesAndOrder() {
        val valid = capture(timeline(listOf("ATTACHED", "IME_VISIBLE", "IME_HIDDEN")))
        val malformed = mutableListOf<Map<String, JsonElement>>()
        for (key in listOf("composer_generation", "composer_events_dropped", "composer_event_1_age_ms")) {
            for (value in listOf(JsonPrimitive(-1), JsonPrimitive("1"), JsonPrimitive(true), JsonPrimitive(1.0), JsonNull, JsonPrimitive(if (key.endsWith("age_ms")) 600_001 else 16_000_001))) malformed += valid.values + (key to value)
        }
        malformed += valid.values + ("composer_generation" to JsonPrimitive(0))
        for (key in listOf("composer_enabled", "composer_window_focused")) for (value in listOf(JsonPrimitive("false"), JsonPrimitive(0), JsonNull)) malformed += valid.values + (key to value)
        for (key in listOf("composer_lifecycle", "composer_input_mode", "composer_event_1")) for (value in listOf(JsonPrimitive("private typed text"), JsonPrimitive(1), JsonNull)) malformed += valid.values + (key to value)
        malformed += valid.values - "composer_generation"
        malformed += valid.values - "composer_events_dropped"
        malformed += valid.values - "composer_event_2_age_ms"
        malformed += valid.values - "composer_event_2"
        malformed += valid.values - setOf("composer_event_2", "composer_event_2_age_ms")
        malformed += valid.values + ("composer_event_1_age_ms" to JsonPrimitive(1))
        malformed += valid.values + ("composer_event_3_age_ms" to JsonPrimitive(1))
        malformed += valid.values + ("composer_event_9" to JsonPrimitive("ATTACHED"))
        for (values in malformed) {
            assertNull(values.toString(), valid.copy(values = values).validated())
            assertNull(IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), valid.copy(values = values))))
        }
        val equalAges = valid.copy(values = valid.values.mapValues { (key, value) -> if (key.endsWith("_age_ms")) JsonPrimitive(0) else value })
        assertTrue(equalAges.isValid())
        assertTrue(capture(timeline(listOf("ATTACHED", "ATTACHED"))).isValid())
        assertTrue(capture(timeline() + mapOf("composer_generation" to 1, "composer_events_dropped" to 0)).isValid())
        assertNull(valid.copy(omittedFields = listOf("composer_event_4", "composer_event_4_age_ms")).validated())
        assertNull(valid.copy(omittedFields = listOf("composer_event_4")).validated())
        assertTrue(capture(emptyMap()).copy(omittedFields = IssueReportDiagnostics.composerSlotFields.toList()).isValid())
        for (key in IssueReportDiagnostics.composerTimelineFields) assertNull(valid.copy(unavailableFields = valid.unavailableFields + key).validated())
    }

    @Test fun malformedLocalTailOmitsWholeSlotGroupWhileKeepingValidStateAndLegacyCounters() {
        val original = timeline(listOf("ATTACHED", "IME_VISIBLE", "IME_HIDDEN"))
        for (fields in listOf(
            original + ("composer_event_3" to "private value"),
            original + ("composer_event_3_age_ms" to true),
            original - "composer_event_3_age_ms",
            original - "composer_generation",
            original + ("composer_events_dropped" to -1),
            original - setOf("composer_event_2", "composer_event_2_age_ms"),
            original + ("composer_event_3_age_ms" to 1)
        )) {
            val local = capture(fields)
            assertTrue(local.isValid())
            assertTrue(local.values.keys.none { it in IssueReportDiagnostics.composerSlotFields })
            for (slot in 1..3) {
                if ("composer_event_$slot" in fields || "composer_event_${slot}_age_ms" in fields) {
                    assertTrue("composer_event_$slot" in local.omittedFields)
                    assertTrue("composer_event_${slot}_age_ms" in local.omittedFields)
                }
            }
            assertEquals(JsonPrimitive(12), local.values["composer_focus_transitions"])
            assertEquals(JsonPrimitive(true), local.values["composer_enabled"])
            assertEquals(2, local.requiredReporterVersion())
            assertFalse(local.values.toString().contains("private value"))
        }
    }

    @Test fun actualByteCompactionOmitsWholeTimelineAndNeverLeavesMissingGenerationOrAnAgePartner() {
        val hash = "a".repeat(64)
        val policy = IssueReportIncidentCapsule.policyFieldNames.sortedByDescending { it.length }.take(8)
        val dense = timeline() + mapOf(
            "exception_type_hashes" to List(8) { hash }, "run_id_hash" to hash, "causal_id_hash" to hash,
            "invocation_id_hash" to hash, "call_id_hash" to hash, "job_id_hash" to hash, "profile_hash" to hash, "model_hash" to hash,
            "incident_phase" to "provider_admission", "causal_provenance" to "MODEL_INVOCATION", "incident_evidence_status" to "CAUSAL_EVIDENCE_UNAVAILABLE",
            "incident_origin" to "APPLICATION", "incident_capture_site" to "CHAT_REPOSITORY_TERMINAL_CATCH", "incident_phase_provenance" to "CAPTURE_BOUNDARY_ONLY",
            "causal_failure_code" to "ENGINEERING_STATE_OR_INPUT_CHANGED", "crash_surface" to "GITHUB_WORKSPACE", "crash_boundary" to "ANDROID_VIEW_DRAW",
            "recovery_observation" to "RECONCILIATION_REQUIRED", "recovery_attempt_count" to 16_000_000,
            "retry_delay_ms" to 86_400_000, "retry_delay_source" to "PROVIDER_HEADER", "provider_wait_state" to "WAITING_PROVIDER_LIMIT",
            "provider_wait_persisted" to true, "recovery_authority" to "UNVERIFIED", "dispatch_policy_stage" to "provider_admission", "dispatch_policy_resume_required" to true,
            "changed_policy_fields" to policy, "changed_policy_field_count" to 8, "changed_policy_unknown_count" to 0,
            "changed_policy_malformed_count" to 0, "changed_policy_omitted_count" to 0, "changed_policy_observation" to "OBSERVED"
        )
        val compacted = capture(dense)
        assertTrue(compacted.isValid())
        assertTrue(compacted.values.keys.none { it in IssueReportDiagnostics.composerSlotFields })
        assertTrue(compacted.omittedFields.containsAll(IssueReportDiagnostics.composerSlotFields))
        assertEquals(2, compacted.requiredReporterVersion())
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), compacted).encodeToByteArray().size <= 3072)
        val context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = 1000).withDiagnostics(compacted)
        val report = IssueReport("11111111-1111-4111-8111-111111111111", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Composer diagnostics", description = "D".repeat(1200), context = context)
        assertTrue(report.approvedPayload().encodeToByteArray().size <= 8192)
        assertNotNull(IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), compacted)))
    }

    @Test fun exactToolByteAllowanceIsOptionalBoundedAndDoesNotInventCumulativeUsage() {
        for (remaining in listOf(0, 1, 1023, 16_000_000)) {
            val capture = capture(mapOf("tool_budget_bytes_available" to remaining), "TOOL")
            assertEquals(mapOf("tool_budget_bytes_available" to JsonPrimitive(remaining)), capture.values)
            assertEquals(2, capture.requiredReporterVersion())
            assertTrue("EXECUTION_OUTCOME_DIAGNOSTICS_V1" in capture.requiredServiceFeatures())
            assertEquals(capture, IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), capture)))
            assertNull(capture.values["tool_result_bytes_used"])
            assertNull(capture.values["tool_result_bytes_limit"])
            for (bad in listOf(JsonPrimitive(-1), JsonPrimitive(16_000_001), JsonPrimitive("0"), JsonPrimitive(false), JsonPrimitive(0.0), JsonNull, JsonArray(emptyList()))) assertNull(capture.copy(values = mapOf("tool_budget_bytes_available" to bad)).validated())
        }
        assertTrue(capture(mapOf("tool_budget_bytes_available" to 0)).values.isEmpty())
    }

    @Test fun localGraphAttemptAllowsOnlyTheCapturedDigestWithoutInventingMissingEvidence() {
        val field = "graph_index_attempt_hash"
        val hash = "a".repeat(64)
        val observed = capture(mapOf(field to hash), "TOOL")
        assertEquals(mapOf(field to JsonPrimitive(hash)), observed.values)
        assertEquals(2, observed.requiredReporterVersion())
        assertEquals(observed, IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), observed)))
        for (bad in listOf(JsonPrimitive("A".repeat(64)), JsonPrimitive("a".repeat(63)), JsonPrimitive("g".repeat(64)), JsonPrimitive("private local UUID"), JsonPrimitive(1), JsonNull)) {
            assertNull(observed.copy(values = mapOf(field to bad)).validated())
            assertTrue(capture(mapOf(field to bad), "TOOL").values.isEmpty())
        }
        val absent = capture(emptyMap(), "TOOL")
        assertTrue(absent.values.isEmpty())
        assertTrue(absent.unavailableFields.none { it == field || it == "tool_budget_bytes_available" })
        assertNull(absent.copy(unavailableFields = absent.unavailableFields + field).validated())
        val omitted = absent.copy(omittedFields = listOf(field))
        assertTrue(omitted.isValid())
        assertEquals(2, omitted.requiredReporterVersion())
        assertTrue(capture(mapOf(field to hash)).values.isEmpty())
    }
}
