package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPrerequisite
import dev.chungjungsoo.gptmobile.data.audit.AppAuditResult
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import dev.chungjungsoo.gptmobile.data.audit.AppAuditScore
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelProbeSource
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
    val components: List<AppAuditComponentPresentation>,
    val evidenceText: String
) {
    val scoreText: String get() = "Measured check pass score: ${benchmarkMetricText(score.score, "/ 100", 1)}"
    val weightText: String get() = "Passing weight ${score.passedWeight} / measured weight ${score.measuredWeight}"
    val coverageText: String get() = "Exercised ${summary.exercised}/${summary.planned} planned checks · Verified passes ${summary.verified}/${summary.planned} planned checks"
    val verifiedText: String get() = "${summary.verified} of ${summary.planned} checks verified"
    val coverageSummaryText: String get() = "${summary.verified} of ${summary.exercised} measured checks passed · " +
        "${summary.failed} failed · ${summary.blocked} blocked · " +
        "${summary.unavailable} unavailable · ${summary.untested} untested" +
        (if (summary.skipped > 0) " · ${summary.skipped} skipped" else "") +
        (if (summary.canceled > 0) " · ${summary.canceled} canceled" else "")
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
    fun verified(kind: AppAuditEvidenceKind): Int = appAuditSummary(run.copy(plan = entries.filter { it.evidenceKind == kind })).verified
    val evidenceText = "Verified measured evidence: ${verified(AppAuditEvidenceKind.LOCAL_CONTRACT)} local checks · " +
        "${verified(AppAuditEvidenceKind.LIVE_METADATA)} metadata checks · ${verified(AppAuditEvidenceKind.TOOL_EXECUTION)} tool executions · " +
        "${verified(AppAuditEvidenceKind.MODEL_EXECUTION)} model executions · ${verified(AppAuditEvidenceKind.DEVICE_MEASUREMENT)} device measurements"
    return AppAuditPresentation(appAuditSummary(run), appAuditScore(run), components, evidenceText)
}

/** Display-only current labels. Exact UIDs never fall back to a name, URL or another target. */
internal fun appAuditCurrentTargetNames(profiles: List<Pair<String, String>>, connections: List<Pair<String, String>>): Map<String, String> =
    (profiles.flatMap { listOf("gateway:${it.first}" to it.second, "model:${it.first}" to it.second) } + connections.map { "connection:${it.first}" to it.second })
        .groupBy { it.first }
        .mapNotNull { (target, matches) ->
            val name = matches.singleOrNull()?.second?.filterNot(Char::isISOControl)?.trim()?.take(120)?.takeIf(String::isNotBlank)
            name?.let { target to it }
        }.toMap()

/** Navigation uses the exact surviving saved identity, never a historical alias or a matching name. */
internal fun appAuditConnectionUid(item: AppAuditEntryPresentation): String? = item.entry.targetId
    ?.takeIf { it.startsWith("connection:") && item.currentSavedName != null }
    ?.removePrefix("connection:")
    ?.takeIf { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }

internal fun appAuditModelProfileUid(entry: AppAuditPlanEntry): String? = entry.targetId
    ?.takeIf {
        entry.sourceId == AppAuditSelectedModelProbeSource.SOURCE_ID &&
            entry.probeId == AppAuditSelectedModelProbeSource.GENERATION &&
            entry.evidenceKind == AppAuditEvidenceKind.MODEL_EXECUTION &&
            it.startsWith(AppAuditSelectedModelProbeSource.TARGET_PREFIX)
    }
    ?.removePrefix(AppAuditSelectedModelProbeSource.TARGET_PREFIX)
    ?.takeIf { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }

internal fun appAuditConnectionActionLabel(item: AppAuditEntryPresentation): String = when (item.result?.reasonCode ?: item.entry.reasonCode) {
    "MCP_LOCAL_CREDENTIAL_REFRESH_REQUIRED" -> "Reconnect saved connection"
    "MCP_LOCAL_CREDENTIAL_MISSING", "MCP_LOCAL_CREDENTIAL_INVALID", "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE",
    "MCP_AUTHENTICATION_REQUIRED", "AUTHENTICATION_FAILED" -> "Repair credentials and reconnect"
    else -> "Open connection settings and checks"
}

/** A retained observation's age is anchored to its original check, not the time this screen opens. */
internal fun appAuditCacheAgeText(result: AppAuditResult): String? {
    if (result.observationSource != AppAuditObservationSource.CACHE) return null
    val observed = result.observation.observedAtEpochMs ?: return null
    if (observed > result.startedAtEpochMs) return "Observation time is later than this audit check; cache age cannot be established."
    val age = (result.startedAtEpochMs - observed) / 60_000
    return if (age == 0L) "Retained evidence was less than a minute old when this audit check began." else "Retained evidence was $age ${if (age == 1L) "minute" else "minutes"} old when this audit check began."
}

internal fun appAuditRetryabilityText(retryable: Boolean?): String = when (retryable) {
    true -> "Retryable: yes, after the connection's recovery conditions are met."
    false -> "Retryable: no automatic retry. Correct the problem, then explicitly reconnect or check again."
    null -> "Retryable: not determined. Inspect the connection before retrying."
}

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
    if (item.result?.outcome in setOf(AppAuditOutcome.FAIL, AppAuditOutcome.BLOCKED)) {
        when (item.result?.observation?.validationRule) {
            "CREDENTIAL_REAUTHORIZATION_REQUIRED" -> return "The saved OAuth authorization requires a new sign-in. Open this exact connection, sign in again, then reconnect. This local rejection did not send an MCP request; retrying the unchanged credential cannot repair it."
            "PROTOCOL_VERSIONS_ARRAY_REQUIRED", "PROTOCOL_VERSIONS_NONEMPTY_REQUIRED", "PROTOCOL_VERSION_FORMAT_REQUIRED" -> return "The discovery response omitted or malformed supportedVersions. Repair the bridge to advertise a nonempty array of protocol revisions it actually implements, then reconnect to verify negotiation and tools/list. HTTP 200 does not make invalid discovery usable and cannot justify silently switching protocols."
            "DISCOVERY_METHOD_SUPPORTED_REQUIRED" -> return "The endpoint answered JSON-RPC but rejected server/discover. Verify its supported protocol and repair bridge negotiation, then reconnect. This recognized modern unknown-method response does not establish that the endpoint path is missing or that legacy initialization is supported."
            "ENDPOINT_ROUTE_REQUIRED" -> return "The configured endpoint returned HTTP 404 without a recognized MCP method response. Check the URL path and running service, then reconnect. The supported protocol has not been established."
        }
    }
    if (item.result?.observation?.httpStatus == 403 && item.result.reasonCode == "MCP_AUTHENTICATION_REQUIRED") {
        return "The service denied permission (HTTP 403). Review the account's access and server policy, then explicitly check the connection again."
    }
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
    "MODEL_EXECUTION_NOT_SELECTED", "MODEL_BENCHMARK_REQUIRED" -> "Model generation is untested in this audit. Prepare this exact saved profile in the model benchmark queue, review the suite, then explicitly run it."
    "MODEL_PROFILE_NOT_CONFIGURED" -> "The selected model profile is unavailable or incomplete. Review that saved profile before preparing a benchmark."
    "MODEL_PROFILE_DISABLED" -> "The selected model profile was disabled for the accepted plan. Its execution remains untested."
    "MODEL_CONFIGURATION_CHANGED" -> "The selected model configuration or credential changed. Start a fresh audit before preparing its benchmark."
    "MODEL_CONFIGURATION_ONLY" -> "The selected model configuration was inspected. Generation, first-token latency and throughput remain untested."
    "AUDIT_SOURCE_CHANGED", "AUDIT_MANIFEST_CHANGED", "AUDIT_VERSION_CHANGED", "AUDIT_INCOMPATIBLE" -> "The app build or audit catalogue changed. Saved evidence is retained; start a new audit for the current version."
    "CONFIGURATION_CHANGED", "AUDIT_AUTHORITY_CHANGED", "PLUGIN_CONFIGURATION_CHANGED" -> "The connection or its authorization changed. Start a new audit with the intended configuration."
    "PROBE_TIMEOUT", "RUN_DEADLINE_EXCEEDED", "REQUEST_TIMEOUT", "MCP_CATALOG_TIMEOUT", "CONNECT_TIMEOUT", "STREAM_IDLE_TIMEOUT" -> "The bounded measurement time expired. This check does not count as successful."
    "RUN_BUDGET_EXHAUSTED", "NETWORK_BUDGET_EXHAUSTED", "AUDIT_BUDGET_EXHAUSTED", "PLUGIN_TARGET_LIMIT" -> "The accepted request budget was exhausted. Remaining checks stay unmeasured."
    "PROBE_EVIDENCE_MISMATCH", "PROBE_CONTRACT_INVALID", "DISPATCH_RECEIPT_MISSING" -> "The observation did not establish the required evidence for this check."
    "PROCESS_INTERRUPTION", "DISPATCH_OUTCOME_UNKNOWN", "CHILD_OPERATION_ABORTED" -> "The check was interrupted. An uncertain operation is retained and is not automatically repeated."
    "SERVICE_INTERRUPTION" -> "Android background tracking was interrupted. Recorded evidence is retained; only remaining checks can resume."
    "EXTERNAL_HARNESS_REQUIRED" -> "Requires an external test harness; this in-app audit has not measured it."
    "INVENTORY_UNAVAILABLE" -> "The configured inventory could not be inspected. Its runtime behavior is unverified."
    "MCP_DISCOVERY_VERIFIED" -> "This check covers catalogue metadata. Individual plugin tools were not executed."
    "MCP_EMPTY_CATALOG" -> "No tools advertised. Review tool exposure on the server or exclude this connection from tool execution if it is intentionally resource-only."
    "GATEWAY_CAPABILITY_VERIFIED" -> "This check covers gateway capability metadata. Model and task execution were not measured."
    "PLUGIN_CONFIGURATION_ONLY" -> "Configuration was inspected; plugin runtime behavior was not established."
    "PLUGIN_EXECUTION_NOT_EXERCISED" -> "Tool execution was outside this audit. This result does not verify tool behavior."
    "PLUGIN_DISABLED" -> "This plugin was disabled for the accepted plan."
    "PLUGIN_NOT_INSTALLED" -> "This plugin is not installed. Its behavior remains untested."
    "PLUGIN_NOT_CONFIGURED" -> "Configure the plugin connection before starting a new audit."
    "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE" -> "The saved credential configuration could not be verified. Save the intended credentials or sign in, refresh the connection, then explicitly check again. No current execution is qualified."
    "PLUGIN_PACKAGE_NOT_VERIFIED" -> "The installed plugin package could not be verified. Its runtime behavior remains untested."
    "MCP_RECONNECT_REQUIRED", "MCP_AUTHENTICATION_REQUIRED", "AUTHENTICATION_FAILED" -> "Reconnect or authenticate this service in Tool connections, then start a new audit."
    "MCP_DISCOVERY_SCHEMA_INVALID" -> "The server's protocol-version discovery response failed validation. Inspect the recorded rule and field, correct the server or bridge compatibility, then explicitly check this connection again. This does not identify an individual tool's argument schema."
    "MCP_DISCOVERY_CONFIGURATION" -> "Protocol discovery was rejected or its configuration was invalid. Inspect the recorded HTTP status and validation detail, correct the endpoint, server or bridge, then explicitly check this connection again."
    "MCP_DISCOVERY_UNAVAILABLE" -> "Discovery did not produce a verified result. Its cause is not established. Inspect the failure phase and dispatch evidence, check the saved endpoint and authentication, then run an explicit connection check."
    "MCP_LOCAL_ENDPOINT_INVALID", "MCP_ENDPOINT_CONFIGURATION" -> "The saved endpoint was rejected. Correct its URL and transport settings, then explicitly check this connection again."
    "MCP_LOCAL_AUTH_TYPE_INVALID" -> "The saved authentication type is unsupported. Choose a supported authentication method in this connection's settings before checking again."
    "MCP_LOCAL_CONNECTION_INVALID" -> "The saved connection identity is invalid. Open the intended connection's settings and save it again before checking discovery."
    "MCP_LOCAL_CREDENTIAL_MISSING", "MCP_LOCAL_CREDENTIAL_INVALID" -> "The saved credential is missing or invalid. Replace it or sign in again in this connection's settings. This local rejection did not send an MCP request."
    "MCP_LOCAL_CREDENTIAL_REFRESH_REQUIRED" -> "Refresh required. Reconnect this saved connection to renew its OAuth token, then start a new audit."
    "MCP_BACKOFF_ACTIVE" -> "The connection is still in its recovery cooldown. This audit does not bypass the retry policy."
    "GATEWAY_PROFILE_UNAVAILABLE", "GATEWAY_PROFILE_UNSUPPORTED" -> "The explicitly selected gateway profile is unavailable or unsupported. Select the intended saved gateway profile for a new audit."
    "GATEWAY_ENGINEERING_PROFILE_REQUIRED" -> "Restore the saved job's engineering Gateway connection before checking it."
    "GATEWAY_ENGINEERING_CAPABILITY_VERIFIED" -> "The engineering Gateway reported readiness for this connection. Task execution remains untested."
    "GATEWAY_ENGINEERING_READINESS_UNAVAILABLE", "GATEWAY_ENGINEERING_NOT_READY" -> "The engineering Gateway did not report ready. Check its saved-job service status."
    "GATEWAY_ENGINEERING_GITHUB_REQUIRED", "GATEWAY_ENGINEERING_GITHUB_UNVERIFIED" -> "Verify the GitHub connection on the engineering Gateway host, then check readiness again."
    "GATEWAY_ENGINEERING_AUTHENTICATION_REQUIRED" -> "The Gateway or its configured backend denied access. Review the saved connection and its permissions."
    "GATEWAY_ENGINEERING_TRANSPORT_UNAVAILABLE", "GATEWAY_ENGINEERING_ROUTE_UNAVAILABLE" -> "The engineering Gateway or its configured backend could not answer the readiness request. Check the saved endpoint and service."
    "GATEWAY_ENGINEERING_IDENTITY_CHANGED", "GATEWAY_SAVED_JOB_IDENTITY_UNAVAILABLE", "GATEWAY_SAVED_BINDING_UNAVAILABLE" -> "The original saved job binding could not be verified. Open the job and review its Gateway, account, and model."
    "GATEWAY_SAVED_MODEL_NOT_READY" -> "The saved job's exact model is not ready. Check its load status on the desktop Gateway."
    "CONNECTION_REFUSED", "DNS_FAILED", "DNS_RECOVERY", "ROUTE_UNAVAILABLE", "TLS_FAILURE" -> "The configured service could not be reached securely. Check its connection settings before retrying."
    else -> null
}
