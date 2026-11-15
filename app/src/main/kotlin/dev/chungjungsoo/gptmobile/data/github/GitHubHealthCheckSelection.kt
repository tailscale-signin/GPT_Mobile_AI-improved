package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class GitHubCheckSelectionEvidence(
    val state: String = "UNVERIFIED",
    val reasonCode: String = "CHECK_INVENTORY_UNVERIFIED",
    val rawRollupState: String = "UNKNOWN",
    val observedChecks: Int = 0,
    val applicableChecks: Int = 0,
    val supersededChecks: Int = 0,
    val unreportedCurrentChecks: Int = 0,
    val selectedWorkflowRunIds: List<String> = emptyList(),
    val supersededWorkflowRunIds: List<String> = emptyList(),
    val workflowRunIdsTruncated: Boolean = false
)

internal data class GitHubHealthCheckSelection(
    val checks: List<JsonObject>?,
    val evidence: GitHubCheckSelectionEvidence
)

private data class WorkflowCheckIdentity(val app: String, val workflow: String, val event: String, val branch: String)

private data class WorkflowCheck(
    val node: JsonObject,
    val name: String,
    val identity: WorkflowCheckIdentity,
    val suite: String,
    val run: String,
    val number: Int,
    val observedAttempt: Int
)

/** Supersession requires a GitHub workflow identity, never a name or a timestamp alone. */
internal fun selectGitHubHealthChecks(checks: List<JsonObject>, expectedHead: String): GitHubHealthCheckSelection {
    fun JsonObject.obj(key: String) = this[key] as? JsonObject
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 512 }
    fun unknown(reason: String) = GitHubHealthCheckSelection(null, GitHubCheckSelectionEvidence(state = "UNKNOWN", reasonCode = reason, observedChecks = checks.size))

    val ids = checks.map { it.text("id") ?: return unknown("CHECK_IDENTITY_MISSING") }
    if (ids.toSet().size != ids.size) return unknown("CHECK_IDENTITY_DUPLICATED")
    val independent = mutableListOf<JsonObject>()
    val workflowChecks = mutableListOf<WorkflowCheck>()
    val externalChecks = mutableSetOf<Pair<String, String>>()
    val statusContexts = mutableSetOf<String>()
    for (check in checks) {
        when (check.text("__typename")) {
            "StatusContext" -> {
                val name = check.text("context") ?: return unknown("STATUS_CONTEXT_IDENTITY_MISSING")
                if (!statusContexts.add(name)) return unknown("STATUS_CONTEXT_IDENTITY_AMBIGUOUS")
                independent.add(check)
            }
            "CheckRun" -> {
                val name = check.text("name") ?: return unknown("CHECK_IDENTITY_MISSING")
                val suite = check.obj("checkSuite") ?: return unknown("CHECK_SUITE_IDENTITY_MISSING")
                val suiteId = suite.text("id") ?: return unknown("CHECK_SUITE_IDENTITY_MISSING")
                val app = suite.obj("app")?.text("id") ?: return unknown("CHECK_APP_IDENTITY_MISSING")
                if (suite.obj("commit")?.text("oid") != expectedHead) return unknown("CHECK_REVISION_UNVERIFIED")
                if (suite["workflowRun"] == JsonNull) {
                    // External apps have no comparable workflow run number. Retain their checks;
                    // duplicate contexts cannot be safely ordered from check IDs or timestamps.
                    if (!externalChecks.add(app to name)) return unknown("EXTERNAL_CHECK_IDENTITY_AMBIGUOUS")
                    independent.add(check)
                    continue
                }
                val run = suite.obj("workflowRun") ?: return unknown("WORKFLOW_RUN_IDENTITY_MISSING")
                val runId = run.text("id") ?: return unknown("WORKFLOW_RUN_IDENTITY_MISSING")
                val workflow = run.obj("workflow")?.text("id") ?: return unknown("WORKFLOW_IDENTITY_MISSING")
                val event = run.text("event") ?: return unknown("WORKFLOW_EVENT_MISSING")
                val branch = suite.obj("branch")?.text("id") ?: return unknown("WORKFLOW_BRANCH_IDENTITY_MISSING")
                val number = (run["runNumber"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return unknown("WORKFLOW_RUN_NUMBER_MISSING")
                val attempt = (run["runAttempt"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return unknown("WORKFLOW_RUN_ATTEMPT_MISSING")
                workflowChecks.add(WorkflowCheck(check, name, WorkflowCheckIdentity(app, workflow, event, branch), suiteId, runId, number, attempt))
            }
            else -> return unknown("CHECK_TYPE_UNRECOGNIZED")
        }
    }
    // A changing attempt or conflicting run/suite association is an inconsistent snapshot.
    if (workflowChecks.groupBy { it.run }.values.any { run ->
            run.map { listOf(it.identity, it.suite, it.number, it.observedAttempt) }.distinct().size != 1
        } ||
        workflowChecks.groupBy { it.suite }.values.any { suite -> suite.map { it.run }.distinct().size != 1 }
    ) {
        return unknown("WORKFLOW_RUN_IDENTITY_CONFLICT")
    }

    val selected = mutableListOf<WorkflowCheck>()
    val superseded = mutableListOf<WorkflowCheck>()
    var unreportedCurrentChecks = 0
    for (runs in workflowChecks.groupBy { it.identity }.values) {
        if (runs.groupBy { it.number }.values.any { sameNumber -> sameNumber.map { it.run }.distinct().size != 1 }) return unknown("WORKFLOW_RUN_NUMBER_AMBIGUOUS")
        // workflowRun.runAttempt describes the run as a whole; it does not prove which
        // attempt produced an individual check. Same-run duplicate names stay unknown.
        if (runs.groupBy { it.run to it.name }.values.any { it.size != 1 }) return unknown("CHECK_ATTEMPT_IDENTITY_AMBIGUOUS")
        if (runs.groupBy { it.name }.values.any { sameName -> sameName.map { it.node["isRequired"] }.distinct().size != 1 }) return unknown("REQUIRED_CHECK_POLICY_CHANGED")
        val latestNumber = runs.maxOf { it.number }
        val latest = runs.filter { it.number == latestNumber }
        val older = runs.filter { it.number < latestNumber }
        val latestNames = latest.map { it.name }.toSet()
        // A run may still be creating jobs. Do not inherit an old success for a missing job
        // or silently discard a failing matrix variant which has no current replacement.
        unreportedCurrentChecks += older.map { it.name }.toSet().count { it !in latestNames }
        selected.addAll(latest)
        superseded.addAll(older)
    }
    val selectedIds = selected.map { it.run }.distinct()
    val supersededIds = superseded.map { it.run }.distinct()
    val applicable = independent + selected.map { it.node }
    return GitHubHealthCheckSelection(
        applicable,
        GitHubCheckSelectionEvidence(
            state = if (unreportedCurrentChecks == 0) "VERIFIED" else "INCOMPLETE",
            reasonCode = when {
                unreportedCurrentChecks > 0 -> "LATEST_RUN_CHECK_COVERAGE_INCOMPLETE"
                superseded.isEmpty() -> "NO_SUPERSEDED_WORKFLOW_RUNS"
                else -> "SUPERSEDED_WORKFLOW_RUNS_EXCLUDED"
            },
            observedChecks = checks.size,
            applicableChecks = applicable.size,
            supersededChecks = superseded.size,
            unreportedCurrentChecks = unreportedCurrentChecks,
            selectedWorkflowRunIds = selectedIds.take(20),
            supersededWorkflowRunIds = supersededIds.take(20),
            workflowRunIdsTruncated = selectedIds.size > 20 || supersededIds.size > 20
        )
    )
}
