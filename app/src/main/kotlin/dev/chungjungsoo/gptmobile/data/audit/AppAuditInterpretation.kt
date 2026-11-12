package dev.chungjungsoo.gptmobile.data.audit

/** Configuration choices and missing evidence stay separate from observed operational failures. */
internal data class AppAuditInterpretation(
    val configurationPasses: Int,
    val disabledTargets: Int,
    val optionalAbsentTargets: Int,
    val credentialBlockers: Int,
    val dependentUntestedChecks: Int
)

internal fun appAuditInterpretation(run: AppAuditRun): AppAuditInterpretation {
    val results = run.results.distinctBy { it.entryId }.associateBy { it.entryId }
    val entries = run.plan.distinctBy { it.id }
    fun targetCount(reason: String): Int = entries.filter {
        it.evidenceKind == AppAuditEvidenceKind.CONFIGURATION && results[it.id]?.reasonCode == reason
    }.map { it.targetId ?: it.id }.distinct().size
    return AppAuditInterpretation(
        configurationPasses = entries.count { it.evidenceKind == AppAuditEvidenceKind.CONFIGURATION && results[it.id]?.outcome == AppAuditOutcome.PASS },
        disabledTargets = targetCount("PLUGIN_DISABLED"),
        optionalAbsentTargets = targetCount("PLUGIN_NOT_INSTALLED"),
        credentialBlockers = entries.count { entry ->
            results[entry.id]?.let { result ->
                result.outcome == AppAuditOutcome.BLOCKED &&
                    (result.reasonCode.startsWith("MCP_LOCAL_CREDENTIAL_") || result.observation.failureCategory == "AUTHENTICATION_REQUIRED")
            } == true
        },
        dependentUntestedChecks = entries.count { entry ->
            results[entry.id]?.outcome == AppAuditOutcome.UNTESTED && appAuditBlockingPrerequisiteIds(run, entry).isNotEmpty()
        }
    )
}

/** These IDs refer only to the same frozen target; display names and export aliases are never keys. */
internal fun appAuditBlockingPrerequisiteIds(run: AppAuditRun, entry: AppAuditPlanEntry): List<String> {
    if (entry.evidenceKind !in setOf(AppAuditEvidenceKind.TOOL_EXECUTION, AppAuditEvidenceKind.MODEL_EXECUTION)) return emptyList()
    val target = entry.targetId ?: return emptyList()
    val discoveryTarget = target.takeIf { it.startsWith(AppAuditSelectedModelProbeSource.TARGET_PREFIX) }
        ?.let { AppAuditPluginProbeSource.GATEWAY_TARGET_PREFIX + it.removePrefix(AppAuditSelectedModelProbeSource.TARGET_PREFIX) } ?: target
    val results = run.results.distinctBy { it.entryId }.associateBy { it.entryId }
    return run.plan.distinctBy { it.id }.filter { prerequisite ->
        (
            prerequisite.targetId == target &&
                prerequisite.evidenceKind == AppAuditEvidenceKind.CONFIGURATION ||
                prerequisite.targetId == discoveryTarget &&
                prerequisite.sourceId == AppAuditPluginProbeSource.SOURCE_ID &&
                prerequisite.probeId in setOf(AppAuditPluginProbeSource.MCP_DISCOVERY, AppAuditPluginProbeSource.GATEWAY_CAPABILITIES)
            ) &&
            results[prerequisite.id]?.let { it.outcome != AppAuditOutcome.PASS || it.observation.configChanged } != false
    }.map { it.id }
}
