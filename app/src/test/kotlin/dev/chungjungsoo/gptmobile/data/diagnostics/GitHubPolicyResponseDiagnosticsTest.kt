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

class GitHubPolicyResponseDiagnosticsTest {
    private val time = 1_800_000_000_000L
    private val feature = IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE
    private val family = IssueReportIncidentCapsule.policyResponseFields
    private val headerFields = listOf("status_policy_protection_permission_header_names", "status_policy_rules_permission_header_names")
    private val headers = listOf("REPORTED_OAUTH_SCOPES", "ACCEPTED_OAUTH_SCOPES", "ACCEPTED_GITHUB_PERMISSIONS", "SSO")

    private fun capture(fields: Map<String, Any?> = emptyMap(), area: String = "ACCEPTANCE"): IssueReportContext = requireNotNull(
        IssueReportContext.fromFields(
            "CHAT_FAILURE",
            IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "GITHUB_TASK_INCOMPLETE", mapOf("diagnostic_area" to area) + fields),
            time
        )
    )

    private fun report(context: IssueReportContext) = IssueReport(
        "8318933a-3c96-4b75-af57-4973a70641e7", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "chat", errorCode = "GITHUB_TASK_INCOMPLETE", context = context
    )

    @Test fun policyResponsesPreserveMeasuredEmptyAndEveryCanonicalHeaderSubset() {
        for (key in headerFields) {
            for (mask in 0 until 16) {
                val observed = headers.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val context = capture(
                    mapOf(key to observed, "response_body" to "private-message", "X-OAuth-Scopes" to "private-scope", "url" to "https://private.invalid/repo")
                )
                val diagnostic = requireNotNull(context.diagnostics)
                assertEquals(setOf(key), diagnostic.values.keys.intersect(family))
                assertEquals(JsonArray(observed.map(::JsonPrimitive)), diagnostic.values[key])
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                assertNull(context.dispatched)
                assertNull(context.remoteAcceptance)
                assertNull(context.retryable)
                assertNull(diagnostic.values["status_policy_complete"])
                val approved = report(context).approvedPayload()
                assertFalse(approved.contains("private"))
                assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
            }
        }
        for ((key, kinds) in mapOf(
            "status_policy_protection_404_kind" to listOf("NOT_FOUND", "OTHER_404", "EXPLICIT_BRANCH_NOT_PROTECTED"),
            "status_policy_rules_404_kind" to listOf("NOT_FOUND", "OTHER_404")
        )) {
            for (kind in kinds) {
                val diagnostic = requireNotNull(capture(mapOf(key to kind)).diagnostics)
                assertEquals(setOf(key), diagnostic.values.keys.intersect(family))
                assertEquals(JsonPrimitive(kind), diagnostic.values[key])
            }
        }
        val absent = requireNotNull(capture().diagnostics)
        assertTrue(absent.values.keys.intersect(family).isEmpty())
        assertFalse(feature in absent.requiredServiceFeatures())
    }

    @Test fun malformedPolicyResponseFieldsNeverBecomeMeasuredEmptyLists() {
        val base = requireNotNull(capture().diagnostics)
        val badLists: List<JsonElement> = listOf(
            JsonNull, JsonPrimitive("SSO"), JsonObject(emptyMap()), JsonArray(listOf(JsonNull)),
            JsonArray(listOf(JsonPrimitive(true))), JsonArray(listOf(JsonPrimitive(1))),
            JsonArray(listOf(JsonPrimitive("private-scope"))), JsonArray(listOf(JsonPrimitive("SSO"), JsonPrimitive("private-scope"))),
            JsonArray(List(2) { JsonPrimitive("SSO") }), JsonArray(List(5) { JsonPrimitive("SSO") }),
            JsonArray(headers.reversed().map(::JsonPrimitive))
        )
        for (key in headerFields) {
            for (bad in badLists) {
                val observed = requireNotNull(capture(mapOf(key to bad, "status_policy_protection_404_kind" to "OTHER_404")).diagnostics)
                assertFalse("$key=$bad", key in observed.values)
                assertEquals(JsonPrimitive("OTHER_404"), observed.values["status_policy_protection_404_kind"])
                assertNull("restored $key=$bad", base.copy(values = base.values + (key to bad)).validated())
            }
        }
        for (key in family - headerFields.toSet()) {
            val malformed = listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()), JsonPrimitive(true), JsonPrimitive(404), JsonPrimitive("private-message")) +
                if (key == "status_policy_rules_404_kind") listOf(JsonPrimitive("EXPLICIT_BRANCH_NOT_PROTECTED")) else emptyList()
            for (bad in malformed) {
                assertFalse(key in requireNotNull(capture(mapOf(key to bad)).diagnostics).values)
                assertNull(base.copy(values = base.values + (key to bad)).validated())
            }
        }
    }

    @Test fun policyResponseContractPreservesAreaSchemaAndCaptureBoundaries() {
        val key = headerFields.first()
        val measured = JsonArray(emptyList())
        for (area in IssueReportDiagnostics.areas - "ACCEPTANCE") {
            val other = requireNotNull(capture(mapOf(key to measured), area).diagnostics)
            assertFalse(key in other.values)
            assertNull(other.copy(values = other.values + (key to measured)).validated())
            assertNull(other.copy(omittedFields = listOf(key)).validated())
            assertNull(other.copy(unavailableFields = other.unavailableFields + key).validated())
        }
        val diagnostic = requireNotNull(capture(mapOf(key to measured)).diagnostics)
        assertNull(diagnostic.copy(schemaVersion = 1).validated())
        assertNull(diagnostic.copy(captureMode = "NO_CAPTURE").validated())
        val disabled = requireNotNull(capture(mapOf("diagnostic_capture_mode" to "NO_CAPTURE", key to measured)).diagnostics)
        assertTrue(disabled.values.isEmpty())
        assertFalse(feature in disabled.requiredServiceFeatures())
        val selected = requireNotNull(capture(mapOf("diagnostic_capture_mode" to "SELECTED_CAPTURE", key to measured)).diagnostics)
        assertEquals("SELECTED_CAPTURE", selected.captureMode)
        assertEquals(measured, selected.values[key])
        assertNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "ACCEPTANCE", "diagnostic_capture_mode" to "unknown", key to measured), time))
    }

    @Test fun partialOmissionsAndUnavailableNamesStillRequirePolicyResponseSupport() {
        val context = capture()
        val base = requireNotNull(context.diagnostics)
        for (key in family) {
            val forms = listOf(base.copy(omittedFields = listOf(key)), base.copy(unavailableFields = base.unavailableFields + key))
            for (diagnostic in forms) {
                assertTrue(diagnostic.isValid())
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                assertTrue(diagnostic.values.keys.intersect(family).isEmpty())
                val approved = report(context.withDiagnostics(diagnostic)).approvedPayload()
                val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), approved)
                assertEquals(diagnostic, restored.context?.diagnostics)
                assertEquals(approved, restored.approvedPayload())
                assertTrue(feature in requireNotNull(restored.context?.diagnostics).requiredServiceFeatures())
            }
        }
        val key = headerFields.first()
        assertNull(base.copy(values = base.values + (key to JsonArray(emptyList())), unavailableFields = base.unavailableFields + key).validated())
        assertNull(base.copy(unavailableFields = base.unavailableFields + listOf(key, key)).validated())
        assertNull(base.copy(unavailableFields = base.unavailableFields + "status_policy_private_response").validated())
    }

    @Test fun boundedAcceptanceCompactionKeepsPolicyCapabilityAndIndependentFields() {
        val context = capture(
            mapOf(
                headerFields[0] to headers, headerFields[1] to headers,
                "status_policy_protection_404_kind" to "OTHER_404", "status_policy_rules_404_kind" to "NOT_FOUND",
                "run_id" to "private-run", "failed_stage" to "acceptance_verification", "checkpoint_hash" to "a".repeat(64),
                "unmet_criterion_id_hashes" to List(8) { "b".repeat(64) }, "unmet_criterion_reason_hashes" to List(8) { "c".repeat(64) },
                "unmet_criterion_categories" to List(8) { "BUILD_REVISION_BINDING" }, "unmet_criterion_states" to List(8) { "NOT_TESTED" },
                "unmet_criterion_blockers" to List(8) { "WORKFLOW_RUN_OR_JOB_REQUIRED" }, "unmet_criterion_count" to 11, "unmet_criterion_omitted_count" to 3,
                "evidence_ref_hashes" to List(8) { "d".repeat(64) },
                "effective_call_limit" to 256, "used_calls" to 82, "remaining_calls" to 174, "pending_continuations" to 16,
                "required_capability_count" to 53, "verified_capability_count" to 44, "blocked_capability_count" to 2, "untested_capability_count" to 7,
                "acceptance_capability_scope" to "FULL_CATALOG", "acceptance_pagination_mode" to "COMPLETE_INVENTORY_REQUESTED"
            )
        )
        val diagnostic = requireNotNull(context.diagnostics)
        assertTrue(diagnostic.isValid())
        assertTrue(diagnostic.omittedFields.isNotEmpty())
        assertEquals(family, (diagnostic.values.keys + diagnostic.omittedFields).intersect(family))
        assertTrue(feature in diagnostic.requiredServiceFeatures())
        for (key in headerFields) diagnostic.values[key]?.let { assertEquals(JsonArray(headers.map(::JsonPrimitive)), it) }
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        val approved = report(context).approvedPayload()
        assertTrue(approved.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        assertFalse(approved.contains("private"))
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
    }
}
