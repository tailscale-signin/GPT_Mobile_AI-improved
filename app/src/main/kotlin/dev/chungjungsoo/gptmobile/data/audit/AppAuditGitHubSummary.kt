package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class AppAuditGitHubSection(
    val summary: AppAuditSummary,
    val executed: Int,
    val localScore: AppAuditScore,
    val liveScore: AppAuditScore
)

internal data class AppAuditGitHubSummary(
    val overall: AppAuditGitHubSection,
    val categories: List<Pair<AppAuditGitHubCategory, AppAuditGitHubSection>>
)

/** A successful local fixture never supplies a live GitHub score. */
internal fun appAuditGitHubSummary(run: AppAuditRun): AppAuditGitHubSummary? {
    val plan = run.plan.distinctBy { it.id }.filter { AppAuditGitHubCatalog.category(it) != null }
    if (plan.isEmpty()) return null
    fun section(entries: List<AppAuditPlanEntry>): AppAuditGitHubSection {
        val ids = entries.map { it.id }.toSet()
        val scoped = run.copy(plan = entries, results = run.results.filter { it.entryId in ids }.distinctBy { it.entryId })
        return AppAuditGitHubSection(
            appAuditSummary(scoped),
            scoped.results.count { it.attempted },
            appAuditScore(scoped.copy(plan = entries.filter { it.evidenceKind == AppAuditEvidenceKind.LOCAL_CONTRACT })),
            appAuditScore(scoped.copy(plan = entries.filter { it.evidenceKind == AppAuditEvidenceKind.LIVE_METADATA && it.safetyClass == AppAuditSafetyClass.NETWORK_READ }))
        )
    }
    return AppAuditGitHubSummary(
        section(plan),
        AppAuditGitHubCategory.entries.mapNotNull { category ->
            plan.filter { AppAuditGitHubCatalog.category(it) == category }.takeIf { it.isNotEmpty() }?.let { category to section(it) }
        }
    )
}

internal fun appAuditGitHubSummaryJson(run: AppAuditRun, completeEvidence: Boolean) = appAuditGitHubSummary(run)?.let { github ->
    fun section(value: AppAuditGitHubSection) = buildJsonObject {
        put("planned", value.summary.planned)
        put("executed", value.executed)
        put("measured", value.summary.exercised)
        put("verifiedPasses", value.summary.verified)
        put("failed", value.summary.failed)
        put("blocked", value.summary.blocked)
        put("unavailable", value.summary.unavailable)
        put("untested", value.summary.untested)
        put("skipped", value.summary.skipped)
        put("canceled", value.summary.canceled)
        fun score(value: AppAuditScore) = buildJsonObject {
            put("value", value.score?.takeIf { completeEvidence }?.let(::JsonPrimitive) ?: JsonNull)
            put("passedWeight", value.passedWeight)
            put("measuredWeight", value.measuredWeight)
        }
        put("localContractScore", score(value.localScore))
        put("liveReadScore", score(value.liveScore))
    }
    buildJsonObject {
        put("policy", "github-audit-separate-evidence-v1")
        put("meaning", "Each score is passing / measured pass-or-fail weight within its evidence class. Local contracts do not verify live GitHub. Successful bounded read responses do not qualify repository writes, complete pagination, CI success, merges or device execution.")
        put("scope", "At most two saved GitHub connections and their selected workspace repository/ref; repository identifiers and response bodies are omitted.")
        put("maxConnections", AppAuditGitHubCatalog.MAX_CONNECTIONS)
        put("readsPerConnection", AppAuditGitHubCatalog.live.size)
        put("maxRequestsPerRead", AppAuditGitHubCatalog.MAX_REQUESTS_PER_READ)
        put("maxGitHubRequests", AppAuditGitHubCatalog.MAX_CONNECTIONS * AppAuditGitHubCatalog.live.size * AppAuditGitHubCatalog.MAX_REQUESTS_PER_READ)
        put("pageSize", AppAuditGitHubCatalog.PAGE_SIZE)
        put("completeRetainedEvidence", completeEvidence)
        put("summary", section(github.overall))
        put(
            "categories",
            JsonArray(
                github.categories.map { (category, value) ->
                    buildJsonObject {
                        put("id", category.id)
                        put("title", category.title)
                        put("summary", section(value))
                    }
                }
            )
        )
    }
} ?: JsonNull
