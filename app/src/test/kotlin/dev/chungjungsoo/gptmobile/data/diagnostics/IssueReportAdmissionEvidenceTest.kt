package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import dev.chungjungsoo.gptmobile.data.database.ToolEventPersistenceFailure
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptancePublicationEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubReviewComparison
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportAdmissionEvidenceTest {
    private val time = 1_800_000_000_000L

    private fun capture(area: String, fields: Map<String, Any?> = emptyMap(), source: String = "CHAT_FAILURE", code: String = "CHAT_FAILED"): IssueReportContext =
        requireNotNull(
            IssueReportContext.fromFields(
                source,
                IssueReportIncidentCapsule.fields(ReportFeature.CHAT, source, code, mapOf("diagnostic_area" to area) + fields),
                time
            )
        )

    private fun report(context: IssueReportContext): IssueReport = IssueReport(
        "8318933a-3c96-4b75-af57-4973a70641e7", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "chat", errorCode = "CHAT_FAILED", context = context
    )

    @Test fun repeatAdmissionScalarsSurviveApprovedPayloadWithoutReconstructingOmittedOperands() {
        val observations = mapOf(
            "failed_stage" to "tool_precheck", "dispatched" to false,
            "dependency_admission_reasons" to listOf("REPEAT_LIMIT"),
            "dependency_repeat_scope" to "TOOL_NAME_AND_ARGUMENTS",
            "dependency_repeat_prior_admissions" to 2, "dependency_repeat_batch_admissions" to 1,
            "dependency_repeat_used" to 3, "dependency_repeat_limit" to 3,
            "url" to "https://private.example/secret", "dependency_repeat_key" to "private-key"
        )
        val context = capture("TOOL", observations, source = "TOOL_RESULT", code = "TOOL_FAILURE")
        val diagnostics = requireNotNull(context.diagnostics)
        val family = IssueReportIncidentCapsule.repeatAdmissionFields
        assertEquals(family, diagnostics.values.keys.intersect(family))
        assertEquals(JsonPrimitive(2), diagnostics.values["dependency_repeat_prior_admissions"])
        assertEquals(JsonPrimitive(1), diagnostics.values["dependency_repeat_batch_admissions"])
        assertEquals(JsonPrimitive(3), diagnostics.values["dependency_repeat_used"])
        assertEquals(JsonPrimitive(3), diagnostics.values["dependency_repeat_limit"])
        assertEquals(false, context.dispatched)
        assertNull(context.httpStatus)
        assertNull(context.remoteAcceptance)
        assertTrue(IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE in diagnostics.requiredServiceFeatures())
        val approved = report(context).approvedPayload()
        assertFalse(approved.contains("private"))
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())

        for (retained in family + "") {
            val omitted = family.filter { it != retained }
            val subset = diagnostics.copy(values = diagnostics.values.filterKeys { it !in family || it == retained }, omittedFields = omitted)
            assertTrue(subset.isValid())
            assertEquals(if (retained.isEmpty()) emptySet<String>() else setOf(retained), subset.values.keys.intersect(family))
            assertTrue(IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE in subset.requiredServiceFeatures())
            val payload = report(context.withDiagnostics(subset)).approvedPayload()
            val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), payload)
            assertEquals(subset, restored.context?.diagnostics)
            assertTrue(payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
            assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), subset).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        }
    }

    @Test fun repeatAdmissionContractRejectsMalformedCountsScopeAliasesAndOtherAreas() {
        val base = requireNotNull(capture("TOOL").diagnostics)
        for (key in IssueReportIncidentCapsule.repeatAdmissionFields) {
            val values = if (key == "dependency_repeat_scope") {
                listOf(JsonPrimitive("TOOL_NAME"), JsonPrimitive("TOOL_NAME_AND_ARGUMENTS"))
            } else {
                listOf(JsonPrimitive(0), JsonPrimitive(16_000_000))
            }
            values.forEach { assertTrue("$key=$it", base.copy(values = base.values + (key to it)).isValid()) }
            val invalid = listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()), JsonPrimitive(true), JsonPrimitive("private-text")) +
                if (key == "dependency_repeat_scope") {
                    listOf(JsonPrimitive("CANONICAL_ARGUMENTS"), JsonPrimitive(1))
                } else {
                    listOf(JsonPrimitive(-1), JsonPrimitive(16_000_001), JsonPrimitive("3"), JsonPrimitive(3.0))
                }
            invalid.forEach { assertNull("$key=$it", base.copy(values = base.values + (key to it)).validated()) }
            for (area in listOf("INCIDENT", "ACCEPTANCE", "PROVIDER", "GATEWAY", "APP", "VOICE")) {
                val other = requireNotNull(capture(area).diagnostics)
                assertNull("$area $key value", other.copy(values = other.values + (key to values.first())).validated())
                assertNull("$area $key omission", other.copy(omittedFields = listOf(key)).validated())
            }
        }
        for (key in listOf("tool_repeat_used", "repeat_limit", "dependency_repeat_key", "dependency_repeat_arguments_hash")) {
            assertNull(base.copy(values = base.values + (key to JsonPrimitive(3))).validated())
        }
        assertFalse(IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE in base.requiredServiceFeatures())
    }

    @Test fun typedPersistenceCaptureAddsOnlyLocalFactsAndPreservesRetainedUnknownOutcome() {
        val failure = ToolEventPersistenceFailure.sequenceExhausted(Int.MIN_VALUE, Int.MAX_VALUE)
        val wrapped = IllegalStateException("private-wrapper", failure)
        val observed = IssueReportIncidentCapsule.exceptionFields(wrapped, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH)
        failure.evidence.forEach { (key, value) -> assertEquals(key, value, observed[key]) }
        assertEquals(JsonPrimitive("CAPTURE_BOUNDARY_ONLY"), observed["incident_phase_provenance"])
        assertEquals(JsonPrimitive("CHAT_REPOSITORY_TERMINAL_CATCH"), observed["incident_capture_site"])
        assertNull(observed["tool_event_assigned_sequence"])
        assertEquals(JsonPrimitive(false), observed["tool_event_write_attempted"])
        for (key in listOf("failed_stage", "dispatched", "retryable", "requires_reconciliation", "remote_acceptance", "partial_output_preserved", "causal_failure_code")) assertNull(key, observed[key])
        val context = capture(
            "INCIDENT",
            observed + mapOf("failed_stage" to "tool_execution", "dispatched" to true, "requires_reconciliation" to true, "retryable" to false, "causal_failure_code" to "RECONCILIATION_REQUIRED"),
            code = "RECONCILIATION_REQUIRED"
        )
        val diagnostic = requireNotNull(context.diagnostics)
        assertEquals(true, context.dispatched)
        assertEquals(true, context.requiresReconciliation)
        assertEquals(false, context.retryable)
        assertEquals("tool_execution", context.stage)
        assertEquals(JsonPrimitive("RECONCILIATION_REQUIRED"), diagnostic.values["causal_failure_code"])
        assertEquals(JsonPrimitive(Int.MIN_VALUE), diagnostic.values["tool_event_requested_sequence"])
        assertEquals(JsonPrimitive(Int.MAX_VALUE), diagnostic.values["tool_event_previous_max_sequence"])
        assertTrue(IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE in diagnostic.requiredServiceFeatures())
        val approved = report(context).approvedPayload()
        assertFalse(approved.contains("private-wrapper"))
        assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
    }

    @Test fun persistenceCaptureRequiresTheActualTypeWithinTheExistingFourCauseBoundary() {
        val typed = ToolEventPersistenceFailure.sequenceExhausted(-1, null)
        val site = IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH
        var caught: Throwable = typed
        repeat(3) { caught = IllegalStateException("private-cause", caught) }
        val lastIncluded = IssueReportIncidentCapsule.exceptionFields(caught, site)
        assertEquals(typed.evidence.keys, lastIncluded.keys.intersect(IssueReportIncidentCapsule.toolEventPersistenceFields))
        val outside = IssueReportIncidentCapsule.exceptionFields(IllegalStateException("private-outer", caught), site)
        assertTrue(outside.keys.intersect(IssueReportIncidentCapsule.toolEventPersistenceFields).isEmpty())
        val untyped = IssueReportIncidentCapsule.exceptionFields(IllegalStateException("TOOL_EVENT_PERSISTENCE_FAILED: SEQUENCE_EXHAUSTED"), site)
        assertTrue(untyped.keys.intersect(IssueReportIncidentCapsule.toolEventPersistenceFields).isEmpty())
        assertEquals(JsonPrimitive(site.name), outside["incident_capture_site"])
        assertEquals(JsonPrimitive(site.name), untyped["incident_capture_site"])
    }

    @Test fun sqliteConstraintClassificationUsesBoundedMarkersAndNeverAnIncidentalTriggerWord() {
        val cases = listOf(
            "UNIQUE constraint failed: private_table.private_column" to "UNIQUE",
            "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)" to "FOREIGN_KEY",
            "NOT NULL constraint failed: private_table.private_column" to "NOT_NULL",
            "CHECK constraint failed: private_trigger_check" to "CHECK",
            "UNIQUE constraint failed: private_table.id (code 1555 SQLITE_CONSTRAINT_PRIMARYKEY)" to "PRIMARY_KEY",
            "private trigger message (code 1811 SQLITE_CONSTRAINT_TRIGGER)" to "TRIGGER",
            "private trigger message" to "UNKNOWN",
            "private SQL contains SQLITE_CONSTRAINT_TRIGGER" to "UNKNOWN",
            "UNIQUE constraint failed: private_table.id (code 1811 SQLITE_CONSTRAINT_TRIGGER)" to "UNKNOWN",
            "private (code 2067 SQLITE_CONSTRAINT_UNIQUE) (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)" to "UNKNOWN",
            "UNIQUE constraint failed: " + "x".repeat(1024) to "UNKNOWN"
        )
        for ((message, expected) in cases) {
            val error = IllegalStateException("private-wrapper", SQLiteConstraintException(message))
            val observed = IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH)
            assertEquals(message, JsonPrimitive(expected), observed["sqlite_constraint_kind"])
            val context = capture("INCIDENT", observed)
            assertTrue(IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE in requireNotNull(context.diagnostics).requiredServiceFeatures())
            assertFalse(report(context).approvedPayload().contains("private"))
        }
        val notSqlite = IssueReportIncidentCapsule.exceptionFields(IllegalStateException("UNIQUE constraint failed: private_table.id"), IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH)
        assertNull(notSqlite["sqlite_constraint_kind"])
        for (area in IssueReportDiagnostics.areas) {
            val base = requireNotNull(capture(area).diagnostics)
            val retained = base.copy(values = base.values + ("sqlite_constraint_kind" to JsonPrimitive("UNKNOWN")))
            assertTrue("$area value", retained.isValid())
            assertTrue(IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE in retained.requiredServiceFeatures())
            val omitted = base.copy(omittedFields = listOf("sqlite_constraint_kind"))
            assertTrue("$area omission", omitted.isValid())
            assertTrue(IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE in omitted.requiredServiceFeatures())
        }
    }

    @Test fun persistenceWireKeepsSignedSequenceBoundsLocalBooleanAndCompactedSubsets() {
        val base = requireNotNull(capture("INCIDENT").diagnostics)
        val allowed = mapOf(
            "tool_event_persistence_operation" to listOf(JsonPrimitive("START")),
            "tool_event_persistence_phase" to listOf(JsonPrimitive("ALLOCATE_SEQUENCE"), JsonPrimitive("INSERT")),
            "tool_event_persistence_failure" to ToolEventPersistenceFailure.Reason.entries.map { JsonPrimitive(it.name) },
            "tool_event_requested_sequence" to listOf(JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(0), JsonPrimitive(Int.MAX_VALUE)),
            "tool_event_previous_max_sequence" to listOf(JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(0), JsonPrimitive(Int.MAX_VALUE)),
            "tool_event_assigned_sequence" to listOf(JsonPrimitive(0), JsonPrimitive(Int.MAX_VALUE)),
            "tool_event_write_attempted" to listOf(JsonPrimitive(false), JsonPrimitive(true))
        )
        assertEquals(IssueReportIncidentCapsule.toolEventPersistenceFields, allowed.keys)
        for ((key, values) in allowed) {
            values.forEach { value ->
                val partial = base.copy(values = base.values + (key to value), omittedFields = (allowed.keys - key).sorted())
                assertTrue("$key=$value", partial.isValid())
                assertEquals(setOf(key), partial.values.keys.intersect(allowed.keys))
                assertTrue(IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE in partial.requiredServiceFeatures())
            }
            val invalid: List<JsonElement> = listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()), JsonPrimitive("private-text")) + when {
                key.endsWith("sequence") -> listOf(JsonPrimitive(Int.MIN_VALUE.toLong() - 1), JsonPrimitive(Int.MAX_VALUE.toLong() + 1), JsonPrimitive("0"), JsonPrimitive(1.0), JsonPrimitive(true)) + if (key == "tool_event_assigned_sequence") listOf(JsonPrimitive(-1)) else emptyList()
                key == "tool_event_write_attempted" -> listOf(JsonPrimitive("true"), JsonPrimitive(0))
                else -> listOf(JsonPrimitive(0), JsonPrimitive(true))
            }
            invalid.forEach { assertNull("$key=$it", base.copy(values = base.values + (key to it)).validated()) }
            for (area in listOf("TOOL", "ACCEPTANCE", "PROVIDER", "GATEWAY", "APP", "VOICE")) {
                val other = requireNotNull(capture(area).diagnostics)
                assertNull("$area $key value", other.copy(values = other.values + (key to values.first())).validated())
                assertNull("$area $key omission", other.copy(omittedFields = listOf(key)).validated())
            }
        }
        assertFalse(IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE in base.requiredServiceFeatures())
    }

    @Test fun acceptanceCauseWireRequiresClosedUniqueTrustFailuresAndRetainsIndependentSubsets() {
        val base = requireNotNull(capture("ACCEPTANCE").diagnostics)
        val key = "acceptance_claim_trust_failures"
        val failures = GitHubAcceptancePublicationEvidence.TrustFailure.entries.map { JsonPrimitive(it.name) }
        for (count in 1..3) {
            val partial = base.copy(values = base.values + (key to JsonArray(failures.take(count))), omittedFields = listOf("acceptance_publication_entry"))
            assertTrue(partial.isValid())
            assertTrue(GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE in partial.requiredServiceFeatures())
            assertNull(partial.values["acceptance_publication_entry"])
        }
        val invalid = listOf<JsonElement>(JsonNull, JsonPrimitive("UNRECOGNIZED_EVIDENCE_OPERATION"), JsonArray(emptyList()), JsonArray(failures + failures.first()), JsonArray(listOf(failures.first(), failures.first())), JsonArray(listOf(JsonPrimitive("private-tool"))), JsonArray(listOf(JsonPrimitive(1))))
        invalid.forEach { assertNull("$key=$it", base.copy(values = base.values + (key to it)).validated()) }
        val entries = GitHubAcceptancePublicationEvidence.Entry.entries
        assertEquals(18, entries.size)
        assertTrue(GitHubAcceptancePublicationEvidence.Entry.LOOKUP_OBSERVATION in entries)
        assertTrue(GitHubAcceptancePublicationEvidence.Entry.EVIDENCE_RETENTION_INCOMPLETE in entries)
        assertNull(base.copy(values = base.values + ("acceptance_publication_entry" to JsonPrimitive("UNKNOWN_ENTRY"))).validated())
        for (entry in entries) {
            val partial = base.copy(values = base.values + ("acceptance_publication_entry" to JsonPrimitive(entry.name)), omittedFields = listOf(key))
            assertTrue(partial.isValid())
            assertNull(partial.values[key])
            assertTrue(GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE in partial.requiredServiceFeatures())
        }
        for (field in GitHubAcceptancePublicationEvidence.observationFields) {
            val value = if (field == key) JsonArray(failures.take(1)) else JsonPrimitive("STATUS_COLLECTION")
            for (area in listOf("INCIDENT", "TOOL", "PROVIDER", "GATEWAY", "APP", "VOICE")) {
                val other = requireNotNull(capture(area).diagnostics)
                assertNull("$area $field", other.copy(values = other.values + (field to value)).validated())
                assertNull("$area $field omitted", other.copy(omittedFields = listOf(field)).validated())
            }
        }
        assertFalse(GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE in base.requiredServiceFeatures())
    }

    @Test fun comparisonProjectionUsesTheActualFailureCheckpointAndRejectsUnqualifiedAliases() {
        val plan = GitHubReviewComparison.Plan(
            buildJsonObject {
                put("state", "BASE_REF_BLOCKED")
                put("comparison_policy", "EXPLICIT_BASE_REF")
                put("base_ref", "private-baseline")
                put("detail", "private-detail")
                put(
                    "baseline_resolution_failure",
                    buildJsonObject {
                        put("error_code", "TOOL_OUTCOME_UNKNOWN")
                        put("dispatched", true)
                        put("requires_reconciliation", true)
                    }
                )
            },
            null
        )
        val failure = GitHubReviewComparison.blockedFailure(plan)
        assertEquals("TOOL_OUTCOME_UNKNOWN", failure.errorCode)
        val original = requireNotNull(failure.failureDetails)
        val context = capture("INCIDENT", original, code = "RECONCILIATION_REQUIRED")
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("BASE_REF_BLOCKED"), diagnostics.values["review_comparison_state"])
        assertEquals(JsonPrimitive("EXPLICIT_BASE_REF"), diagnostics.values["review_comparison_policy"])
        assertEquals(true, context.dispatched)
        assertEquals(true, context.requiresReconciliation)
        assertEquals("review_comparison", context.stage)
        assertTrue(GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE in diagnostics.requiredServiceFeatures())
        assertFalse(report(context).approvedPayload().contains("private-"))
        assertEquals(original, failure.failureDetails)

        val rawAliases = mapOf("review_comparison_state" to "BASE_REF_BLOCKED", "review_comparison_policy" to "EXPLICIT_BASE_REF")
        for (fields in listOf(rawAliases, rawAliases + ("failed_stage" to "review_comparison"), original + ("failed_stage" to JsonPrimitive("provider_generation")), original + ("state" to JsonPrimitive("private-state")) + ("comparison_policy" to JsonPrimitive("private-policy")))) {
            val unqualified = requireNotNull(capture("INCIDENT", fields).diagnostics)
            assertTrue(unqualified.values.keys.intersect(IssueReportIncidentCapsule.reviewComparisonFields).isEmpty())
        }
        assertTrue(requireNotNull(capture("INCIDENT", original, source = "TOOL_RESULT").diagnostics).values.keys.intersect(IssueReportIncidentCapsule.reviewComparisonFields).isEmpty())
        assertTrue(requireNotNull(capture("ACCEPTANCE", original).diagnostics).values.keys.intersect(IssueReportIncidentCapsule.reviewComparisonFields).isEmpty())
        for (key in IssueReportIncidentCapsule.reviewComparisonFields) {
            val partial = diagnostics.copy(values = diagnostics.values.filterKeys { it !in IssueReportIncidentCapsule.reviewComparisonFields || it == key }, omittedFields = (IssueReportIncidentCapsule.reviewComparisonFields - key).toList())
            assertTrue(partial.isValid())
            assertTrue(GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE in partial.requiredServiceFeatures())
        }
    }
}
