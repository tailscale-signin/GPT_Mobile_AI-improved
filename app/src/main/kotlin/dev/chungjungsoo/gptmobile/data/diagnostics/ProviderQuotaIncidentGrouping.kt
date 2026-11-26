package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Groups pending observations before approval; submitted payloads are never rewritten. */
internal object ProviderQuotaIncidentGrouping {
    const val FEATURE = "PROVIDER_QUOTA_GROUP_V1"
    const val CONFIGURATION_FIELD = "provider_configuration_hash"
    val fields = setOf(CONFIGURATION_FIELD, "provider_quota_occurrences", "provider_quota_span_ms", "provider_quota_invocation_hashes")
    private val hash = Regex("[a-f0-9]{64}")

    fun occurrence(report: IssueReport): String? =
        (report.context?.invocationIdHash ?: report.context?.runIdHash)?.takeIf(hash::matches)

    private fun identity(report: IssueReport): List<String?>? {
        val context = report.context ?: return null
        val diagnostic = context.diagnostics ?: return null
        if (report.source != "debug" ||
            report.errorCode != "SOURCE_PAYMENT_REQUIRED" ||
            diagnostic.area != "PROVIDER" ||
            context.requiresReconciliation == true ||
            context.provider == null ||
            context.stage == null
        ) {
            return null
        }
        fun value(key: String) = (diagnostic.values[key] as? JsonPrimitive)?.contentOrNull
        if (value("provider_failure_kind") != "BUDGET" || value("request_replay_allowed") != "false") return null
        val profile = value("profile_hash")?.takeIf(hash::matches) ?: return null
        val model = value("model_hash")?.takeIf(hash::matches) ?: return null
        val configuration = value(CONFIGURATION_FIELD)?.takeIf(hash::matches) ?: return null
        return listOf(report.version, report.build.toString(), report.commit, context.provider, context.stage, profile, model, configuration) +
            listOf("provider_budget_kind", "provider_budget_retry", "provider_budget_limit_source", "provider_budget_reason", "upstream_error_code", "original_error_code_hash", "provider_route_hash").map(::value)
    }

    fun merge(first: IssueReport, next: IssueReport, seen: Set<String>): IssueReport? {
        val groupIdentity = identity(first) ?: return null
        if (groupIdentity != identity(next)) return null
        val firstContext = first.context ?: return null
        val nextContext = next.context ?: return null
        val span = nextContext.occurredAtMillis - firstContext.occurredAtMillis
        if (span !in 0..600_000L) return null
        val firstOccurrence = occurrence(first) ?: return null
        val nextOccurrence = occurrence(next) ?: return null
        if (firstOccurrence !in seen) return null
        if (nextOccurrence in seen) return first
        if (seen.size >= 1024) return null
        val diagnostic = firstContext.diagnostics ?: return null
        val samples = (listOf(firstOccurrence) + seen.toList().takeLast(6) + nextOccurrence).distinct()
        // Keep the first/latest joinable, within the original protected capsule
        // size. If no complete grouping fits, preserve the next report separately.
        val grouped = (samples.size downTo 2).firstNotNullOfOrNull { size ->
            val retained = (samples.take(size - 1) + nextOccurrence).distinct()
            diagnostic.copy(
                values = diagnostic.values + mapOf(
                    "provider_quota_occurrences" to JsonPrimitive(seen.size + 1),
                    "provider_quota_span_ms" to JsonPrimitive(span),
                    "provider_quota_invocation_hashes" to JsonArray(retained.map(::JsonPrimitive))
                ),
                omittedFields = diagnostic.omittedFields - fields,
                unavailableFields = diagnostic.unavailableFields - fields
            ).validated()
        } ?: return null
        // Correlations and approval previews are immutable under their report UUID.
        // A newer group must not rewrite an already retained or approved snapshot.
        return first.copy(reportId = UUID.randomUUID().toString(), context = firstContext.copy(diagnostics = grouped))
    }
}
