package dev.chungjungsoo.gptmobile.data.audit

internal data class AppAuditGitHubTarget(
    val key: String,
    val revision: String,
    val repositorySelected: Boolean,
    val prerequisite: AppAuditPrerequisite = AppAuditPrerequisite.READY,
    val reasonCode: String? = null,
    val read: (suspend (AppAuditGitHubCheck, suspend () -> Unit) -> AppAuditObservation)? = null
)

internal data class AppAuditGitHubInventory(val targets: List<AppAuditGitHubTarget>, val omittedTargets: Int = 0)

internal class AppAuditGitHubConfigurationChanged : IllegalStateException("GitHub audit scope changed")

/** Scope is frozen before optional reads; no repository is inferred from an account or another connection. */
internal class AppAuditGitHubProbeSource(
    private val inventory: suspend () -> AppAuditGitHubInventory,
    private val target: suspend (String) -> AppAuditGitHubTarget?
) : AppAuditProbeSource {
    override val sourceId = AppAuditGitHubCatalog.LIVE_SOURCE

    override suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry> {
        val available = inventory()
        val targets = available.targets.distinctBy { it.key }.sortedBy { it.key }
        val selected = targets.take(AppAuditGitHubCatalog.MAX_CONNECTIONS).ifEmpty {
            listOf(AppAuditGitHubTarget("github-unconfigured", appAuditHash("github-unconfigured"), false, AppAuditPrerequisite.NOT_CONFIGURED, "GITHUB_AUDIT_CONNECTION_REQUIRED"))
        }
        val omitted = (available.omittedTargets.coerceAtLeast(0) + targets.size - minOf(targets.size, AppAuditGitHubCatalog.MAX_CONNECTIONS)).coerceAtMost(4096)
        return selected.flatMap { target -> AppAuditGitHubCatalog.live.map { definition(target, it, options.allowNetworkReads) } } +
            AppAuditGitHubCatalog.external.map { check ->
                AppAuditPlanEntry(
                    id = "$sourceId:${check.id}", sourceId = sourceId, probeId = check.id, componentId = "github", label = check.label,
                    sourceAreaIds = listOf("github"), evidenceKind = AppAuditEvidenceKind.EXTERNAL_SUITE, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY,
                    prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED, reasonCode = "EXTERNAL_HARNESS_REQUIRED"
                )
            } + if (omitted > 0) {
                listOf(
                    AppAuditPlanEntry(
                        id = "$sourceId:target-limit", sourceId = sourceId, probeId = "github_target_limit_v1", componentId = "github",
                        label = requireNotNull(AppAuditGitHubCatalog.labels["github_target_limit_v1"]), targetId = "omitted:$omitted", sourceAreaIds = listOf("github"),
                        evidenceKind = AppAuditEvidenceKind.EXTERNAL_SUITE, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY,
                        prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED, reasonCode = "GITHUB_AUDIT_TARGET_LIMIT"
                    )
                )
            } else {
                emptyList()
            }
    }

    private fun definition(target: AppAuditGitHubTarget, check: AppAuditGitHubCheck, selected: Boolean): AppAuditPlanEntry {
        val missingRepository = check.action != "get_account" && !target.repositorySelected
        val prerequisite = when {
            target.prerequisite != AppAuditPrerequisite.READY -> target.prerequisite
            missingRepository -> AppAuditPrerequisite.NOT_CONFIGURED
            else -> AppAuditPrerequisite.READY
        }
        return AppAuditPlanEntry(
            id = "$sourceId:${check.id}:${appAuditTargetDigest(target.key)}", sourceId = sourceId, probeId = check.id,
            componentId = "github", label = check.label, targetId = target.key, sourceAreaIds = listOf("github"),
            evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, safetyClass = AppAuditSafetyClass.NETWORK_READ, selected = selected,
            prerequisite = prerequisite,
            reasonCode = when {
                target.prerequisite != AppAuditPrerequisite.READY -> target.reasonCode
                missingRepository -> "GITHUB_AUDIT_REPOSITORY_REQUIRED"
                !selected -> "NETWORK_READS_NOT_SELECTED"
                else -> null
            },
            configFingerprint = target.revision, configurationRevision = target.revision
        )
    }

    override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
        context.ensureActive()
        val check = AppAuditGitHubCatalog.live.singleOrNull { it.id == entry.probeId }
            ?: return githubAuditObservation(AppAuditOutcome.UNTESTED, "EXTERNAL_HARNESS_REQUIRED").copy(evidenceKind = entry.evidenceKind)
        if (!entry.selected) return githubAuditObservation(AppAuditOutcome.SKIPPED, "NETWORK_READS_NOT_SELECTED")
        suspend fun current(): AppAuditGitHubTarget? {
            val fresh = entry.targetId?.let { target(it) } ?: return null
            val expected = definition(fresh, check, entry.selected)
            return fresh.takeIf {
                entry.copy(prerequisite = expected.prerequisite, reasonCode = expected.reasonCode) == expected
            }
        }
        val selected = current() ?: return changed()
        fun blocked(target: AppAuditGitHubTarget): AppAuditObservation? {
            val definition = definition(target, check, entry.selected)
            return when (definition.prerequisite) {
                AppAuditPrerequisite.READY -> null
                AppAuditPrerequisite.PERMISSION_REQUIRED -> githubAuditObservation(AppAuditOutcome.BLOCKED, definition.reasonCode ?: "PERMISSION_REQUIRED")
                else -> githubAuditObservation(AppAuditOutcome.UNAVAILABLE, definition.reasonCode ?: "NOT_CONFIGURED")
            }
        }
        blocked(selected)?.let { return it }
        val read = selected.read ?: return githubAuditObservation(AppAuditOutcome.UNAVAILABLE, "GITHUB_AUDIT_CONNECTION_REQUIRED")
        val observed = try {
            read(check) {
                val before = current() ?: throw AppAuditGitHubConfigurationChanged()
                blocked(before)?.let { throw AppAuditGitHubReadBlocked(it) }
                context.beforeDispatch()
                val admitted = current() ?: throw AppAuditGitHubConfigurationChanged()
                blocked(admitted)?.let { throw AppAuditGitHubReadBlocked(it) }
            }
        } catch (_: AppAuditGitHubConfigurationChanged) {
            return changed()
        } catch (failure: AppAuditGitHubReadBlocked) {
            return failure.observation
        }
        context.ensureActive()
        val bound = observed.copy(configurationRevision = entry.configurationRevision)
        val after = current() ?: return bound.copy(outcome = AppAuditOutcome.BLOCKED, reasonCode = "CONFIGURATION_CHANGED", configChanged = true)
        val blocker = blocked(after)
        return if (blocker == null) bound else bound.copy(outcome = blocker.outcome, reasonCode = blocker.reasonCode)
    }

    private fun changed() = githubAuditObservation(AppAuditOutcome.BLOCKED, "CONFIGURATION_CHANGED").copy(configChanged = true)
}

private class AppAuditGitHubReadBlocked(val observation: AppAuditObservation) : IllegalStateException("GitHub audit read blocked")

internal fun githubAuditObservation(outcome: AppAuditOutcome, reason: String) = AppAuditObservation(
    outcome,
    reason,
    stage = "admission",
    evidenceKind = AppAuditEvidenceKind.LIVE_METADATA
)
