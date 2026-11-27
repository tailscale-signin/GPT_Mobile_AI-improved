package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderQuotaIncidentGroupingTest {
    private fun report(id: String, time: Long = 1000, provider: String = "OPENAI", profile: String = "a".repeat(64), extra: Map<String, Any?> = emptyMap()): IssueReport {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf(
                "diagnostic_area" to "PROVIDER",
                "provider" to provider,
                "failed_stage" to "response_stream",
                "run_id" to "run-$id",
                "invocation_id" to "invocation-$id",
                "profile_hash" to profile,
                "model_hash" to "b".repeat(64),
                "provider_configuration_hash" to "e".repeat(64),
                "provider_failure_kind" to "BUDGET",
                "provider_budget_kind" to "UNKNOWN",
                "provider_budget_retry" to "AFTER_CONFIGURATION_CHANGE",
                "request_replay_allowed" to false
            ) + extra,
            time
        )
        return IssueReport(id, "debug", "0.9.66.18", 152, "c".repeat(40), "16", 36, "Fixture", feature = "chat", errorCode = "SOURCE_PAYMENT_REQUIRED", context = context)
    }

    @Test fun repeatedPendingQuotaRetainsTheFirstIncidentAndDistinctInvocationEvidence() {
        val first = report("first")
        val second = report("second", time = 1500)
        val initial = requireNotNull(ProviderQuotaIncidentGrouping.occurrence(first))
        val next = requireNotNull(ProviderQuotaIncidentGrouping.occurrence(second))
        val merged = requireNotNull(ProviderQuotaIncidentGrouping.merge(first, second, setOf(initial)))
        val diagnostic = requireNotNull(merged.context?.diagnostics)
        assertNotEquals(first.reportId, merged.reportId)
        assertEquals(first.context?.runIdHash, merged.context?.runIdHash)
        assertEquals(JsonPrimitive(2), diagnostic.values["provider_quota_occurrences"])
        assertEquals(JsonPrimitive(500), diagnostic.values["provider_quota_span_ms"])
        assertEquals(JsonArray(listOf(JsonPrimitive(initial), JsonPrimitive(next))), diagnostic.values["provider_quota_invocation_hashes"])
        assertNotNull(diagnostic.validated())
        assertTrue(ProviderQuotaIncidentGrouping.FEATURE in diagnostic.requiredServiceFeatures())
        assertEquals(merged, ProviderQuotaIncidentGrouping.merge(merged, second, setOf(initial, next)))
        assertNull(first.context?.diagnostics?.values?.get("provider_quota_occurrences"))
    }

    @Test fun differentProviderProfileCauseOrTimeWindowRetainsSeparateReports() {
        val first = report("first")
        val seen = setOf(requireNotNull(ProviderQuotaIncidentGrouping.occurrence(first)))
        for (next in listOf(
            report("other", provider = "OPENROUTER"),
            report("other", profile = "d".repeat(64)),
            report("other", time = 601_001),
            report("other", time = 999),
            report("other", extra = mapOf("provider_budget_kind" to "KEY_LIMIT")),
            report("other", extra = mapOf("request_replay_allowed" to true)),
            report("other", extra = mapOf("requires_reconciliation" to true)),
            report("other", extra = mapOf("provider_configuration_hash" to "f".repeat(64))),
            report("other", extra = mapOf("provider_configuration_hash" to null)),
            report("other", extra = mapOf("provider_configuration_hash" to "private configuration")),
            report("other", extra = mapOf("profile_hash" to "unverified-profile"))
        )) {
            assertNull(ProviderQuotaIncidentGrouping.merge(first, next, seen))
        }
    }

    @Test fun missingFirstConfigurationCannotBorrowTheNextReportsIdentity() {
        val first = report("first", extra = mapOf("provider_configuration_hash" to null))
        assertNull(ProviderQuotaIncidentGrouping.merge(first, report("next"), setOf(requireNotNull(ProviderQuotaIncidentGrouping.occurrence(first)))))
    }

    @Test fun anAlreadyObservedInvocationDoesNotCreateAnotherReportAtTheDistinctOccurrenceLimit() {
        val first = report("first")
        val initial = requireNotNull(ProviderQuotaIncidentGrouping.occurrence(first))
        val seen = (listOf(initial) + (1..1023).map { it.toString(16).padStart(64, '0') }).toSet()
        assertEquals(first, ProviderQuotaIncidentGrouping.merge(first, first, seen))
        assertNull(ProviderQuotaIncidentGrouping.merge(first, report("next"), seen))
    }

    @Test fun compactedUnknownUpstreamCausesKeepTheirRetainedHashIdentity() {
        val first = report("first", extra = mapOf("original_error_code_hash" to "c".repeat(64)))
        val second = report("second", extra = mapOf("original_error_code_hash" to "d".repeat(64)))
        assertEquals(JsonPrimitive("c".repeat(64)), first.context?.diagnostics?.values?.get("original_error_code_hash"))
        assertNull(ProviderQuotaIncidentGrouping.merge(first, second, setOf(requireNotNull(ProviderQuotaIncidentGrouping.occurrence(first)))))
    }
}
