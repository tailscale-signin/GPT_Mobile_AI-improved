package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPrerequisite
import dev.chungjungsoo.gptmobile.data.audit.AppAuditResult
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import dev.chungjungsoo.gptmobile.data.audit.AppAuditScore
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSummary
import dev.chungjungsoo.gptmobile.data.audit.appAuditScore
import dev.chungjungsoo.gptmobile.data.audit.appAuditSummary
import dev.chungjungsoo.gptmobile.data.audit.appAuditTargetAliases

internal data class AppAuditEntryPresentation(
    val entry: AppAuditPlanEntry,
    val result: AppAuditResult?,
    val active: Boolean,
    val includedInScore: Boolean,
    val targetAlias: String? = null,
    val currentSavedName: String? = null
)

internal data class AppAuditComponentPresentation(
    val id: String,
    val entries: List<AppAuditEntryPresentation>,
    val summary: AppAuditSummary
)

/** Every score is projected from the same immutable run and accepted plan as its coverage. */
internal data class AppAuditPresentation(
    val summary: AppAuditSummary,
    val score: AppAuditScore,
    val components: List<AppAuditComponentPresentation>
) {
    val scoreText: String get() = "Measured check pass score: ${benchmarkMetricText(score.score, "/ 100", 1)}"
    val weightText: String get() = "Passing weight ${score.passedWeight} / measured weight ${score.measuredWeight}"
    val coverageText: String get() = "Exercised ${summary.exercised}/${summary.planned} planned checks · Verified passes ${summary.verified}/${summary.planned} planned checks"
    val countsText: String get() = "Passed ${summary.passed} · Failed ${summary.failed} · Blocked ${summary.blocked} · Unavailable ${summary.unavailable} · Untested or pending ${summary.untested} · Skipped ${summary.skipped} · Canceled ${summary.canceled}"
    val coverageNotice: String get() = when {
        summary.planned == 0 -> "No accepted check plan is available. A score cannot establish coverage."
        summary.exercised < summary.planned -> "Partial coverage: ${summary.planned - summary.exercised} planned checks have no eligible measured result. Finishing this run does not verify all app features."
        else -> "Every planned check has an eligible result. Coverage applies to these named checks, not every behavior in the app."
    }
}

internal fun appAuditPresentation(run: AppAuditRun, currentTargetNames: Map<String, String> = emptyMap()): AppAuditPresentation {
    val results = run.results.distinctBy { it.entryId }.associateBy { it.entryId }
    val entries = run.plan.distinctBy { it.id }
    val targets = appAuditTargetAliases(run.plan)
    val components = entries.groupBy { it.componentId }.map { (id, plan) ->
        AppAuditComponentPresentation(
            id,
            plan.map { entry ->
                val result = results[entry.id]
                AppAuditEntryPresentation(
                    entry,
                    result,
                    run.activeProbeId == entry.id,
                    result != null && appAuditScore(run.copy(plan = listOf(entry), results = listOf(result))).measuredWeight > 0,
                    entry.targetId?.let(targets::get),
                    entry.targetId?.let(currentTargetNames::get)
                )
            },
            appAuditSummary(run.copy(plan = plan))
        )
    }
    return AppAuditPresentation(appAuditSummary(run), appAuditScore(run), components)
}

/** Display-only current labels. Exact UIDs never fall back to a name, URL or another target. */
internal fun appAuditCurrentTargetNames(profiles: List<Pair<String, String>>, connections: List<Pair<String, String>>): Map<String, String> =
    (profiles.map { "gateway:${it.first}" to it.second } + connections.map { "connection:${it.first}" to it.second })
        .groupBy { it.first }
        .mapNotNull { (target, matches) ->
            val name = matches.singleOrNull()?.second?.filterNot(Char::isISOControl)?.trim()?.take(120)?.takeIf(String::isNotBlank)
            name?.let { target to it }
        }.toMap()

internal fun AppAuditEvidenceKind.auditLabel(): String = when (this) {
    AppAuditEvidenceKind.CONFIGURATION -> "Configuration"
    AppAuditEvidenceKind.LOCAL_CONTRACT -> "Local contract"
    AppAuditEvidenceKind.LIVE_METADATA -> "Live metadata"
    AppAuditEvidenceKind.TOOL_EXECUTION -> "Tool execution"
    AppAuditEvidenceKind.MODEL_EXECUTION -> "Model execution"
    AppAuditEvidenceKind.DEVICE_MEASUREMENT -> "Device measurement"
    AppAuditEvidenceKind.EXTERNAL_SUITE -> "External suite"
}

internal fun appAuditOutcomeLabel(outcome: AppAuditOutcome?): String = when (outcome) {
    AppAuditOutcome.PASS -> "Passed"
    AppAuditOutcome.FAIL -> "Failed"
    AppAuditOutcome.SKIPPED -> "Skipped"
    AppAuditOutcome.BLOCKED -> "Blocked"
    AppAuditOutcome.UNAVAILABLE -> "Unavailable"
    AppAuditOutcome.UNTESTED -> "Untested"
    AppAuditOutcome.CANCELED -> "Canceled"
    null -> "Not yet tested"
}

/** Unknown imported/provider codes never become unbounded or misleading UI copy. */
internal fun appAuditReason(item: AppAuditEntryPresentation): String {
    if (item.active) return "This check is in progress."
    val entry = item.entry
    appAuditReasonCodeMessage(item.result?.reasonCode ?: entry.reasonCode)?.let { return it }
    if (!entry.selected) return "Not selected for this run."
    when (entry.prerequisite) {
        AppAuditPrerequisite.NOT_CONFIGURED -> return "The required connection is not configured."
        AppAuditPrerequisite.UNSUPPORTED -> return "This target does not support the named check."
        AppAuditPrerequisite.PERMISSION_REQUIRED -> return "This check requires permission before it can run."
        AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED -> return "Requires an external test harness; this in-app audit has not measured it."
        AppAuditPrerequisite.READY -> Unit
    }
    return when (item.result?.outcome) {
        AppAuditOutcome.PASS -> "The named check passed within its recorded evidence scope."
        AppAuditOutcome.FAIL -> "The named assertion failed. Inspect its recorded evidence."
        AppAuditOutcome.SKIPPED -> "This check was skipped."
        AppAuditOutcome.BLOCKED -> "A required condition prevented this check from completing."
        AppAuditOutcome.UNAVAILABLE -> "The required service or capability was unavailable."
        AppAuditOutcome.UNTESTED -> "This check has no measured observation."
        AppAuditOutcome.CANCELED -> "This check was canceled. It does not count as a successful check."
        null -> "Awaiting a recorded observation."
    }
}

internal fun appAuditReasonCodeMessage(code: String?): String? = when (code) {
    "NETWORK_READS_DISABLED", "NETWORK_READS_NOT_SELECTED" -> "Service reads were disabled for this plan. Enable them and start a new audit to measure this connection."
    "MODEL_EXECUTION_NOT_SELECTED" -> "Model generation is outside this audit. Use the model benchmark queue to measure it."
    "AUDIT_SOURCE_CHANGED", "AUDIT_MANIFEST_CHANGED", "AUDIT_VERSION_CHANGED", "AUDIT_INCOMPATIBLE" -> "The app build or audit catalogue changed. Saved evidence is retained; start a new audit for the current version."
    "CONFIGURATION_CHANGED", "AUDIT_AUTHORITY_CHANGED", "PLUGIN_CONFIGURATION_CHANGED" -> "The connection or its authorization changed. Start a new audit with the intended configuration."
    "PROBE_TIMEOUT", "RUN_DEADLINE_EXCEEDED", "REQUEST_TIMEOUT", "MCP_CATALOG_TIMEOUT", "CONNECT_TIMEOUT", "STREAM_IDLE_TIMEOUT" -> "The bounded measurement time expired. This check does not count as successful."
    "RUN_BUDGET_EXHAUSTED", "NETWORK_BUDGET_EXHAUSTED", "AUDIT_BUDGET_EXHAUSTED", "PLUGIN_TARGET_LIMIT" -> "The accepted request budget was exhausted. Remaining checks stay unmeasured."
    "PROBE_EVIDENCE_MISMATCH", "PROBE_CONTRACT_INVALID", "DISPATCH_RECEIPT_MISSING" -> "The observation did not establish the required evidence for this check."
    "PROCESS_INTERRUPTION", "DISPATCH_OUTCOME_UNKNOWN", "CHILD_OPERATION_ABORTED" -> "The check was interrupted. An uncertain operation is retained and is not automatically repeated."
    "EXTERNAL_HARNESS_REQUIRED" -> "Requires an external test harness; this in-app audit has not measured it."
    "INVENTORY_UNAVAILABLE" -> "The configured inventory could not be inspected. Its runtime behavior is unverified."
    "MCP_DISCOVERY_VERIFIED", "MCP_EMPTY_CATALOG" -> "This check covers catalogue metadata. Individual plugin tools were not executed."
    "GATEWAY_CAPABILITY_VERIFIED" -> "This check covers gateway capability metadata. Model and task execution were not measured."
    "PLUGIN_CONFIGURATION_ONLY" -> "Configuration was inspected; plugin runtime behavior was not established."
    "PLUGIN_EXECUTION_NOT_EXERCISED" -> "Tool execution was outside this audit. This result does not verify tool behavior."
    "PLUGIN_DISABLED" -> "This plugin was disabled for the accepted plan."
    "PLUGIN_NOT_INSTALLED" -> "This plugin is not installed. Its behavior remains untested."
    "PLUGIN_NOT_CONFIGURED" -> "Configure the plugin connection before starting a new audit."
    "PLUGIN_PACKAGE_NOT_VERIFIED" -> "The installed plugin package could not be verified. Its runtime behavior remains untested."
    "MCP_RECONNECT_REQUIRED", "MCP_AUTHENTICATION_REQUIRED", "AUTHENTICATION_FAILED" -> "Reconnect or authenticate this service in Tool connections, then start a new audit."
    "MCP_BACKOFF_ACTIVE" -> "The connection is still in its recovery cooldown. This audit does not bypass the retry policy."
    "GATEWAY_PROFILE_UNAVAILABLE", "GATEWAY_PROFILE_UNSUPPORTED" -> "The explicitly selected gateway profile is unavailable or unsupported. Select the intended saved gateway profile for a new audit."
    "CONNECTION_REFUSED", "DNS_FAILED", "DNS_RECOVERY", "ROUTE_UNAVAILABLE", "TLS_FAILURE" -> "The configured service could not be reached securely. Check its connection settings before retrying."
    else -> null
}
