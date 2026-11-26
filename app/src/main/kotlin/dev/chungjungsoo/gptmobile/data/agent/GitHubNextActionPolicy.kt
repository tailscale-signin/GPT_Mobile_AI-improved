package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceWork
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubNextActionMode
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A planning view over one accepted checkpoint. The executor and verifier keep their own admission boundaries. */
internal object GitHubNextActionPolicy {
    enum class Action { RECONCILE, REUSE_EVIDENCE, ACQUIRE, VERIFY, WAIT, DISCOVER }

    /** Bind a selected-revision diagnosis to its accepted target, never a historical failure. */
    fun scopedCiRead(
        call: ProviderEvent.ToolCall,
        head: String,
        repository: String,
        branch: String?,
        receipts: Map<String, JsonObject>
    ): ProviderEvent.ToolCall? {
        if (call.name.substringBefore("__") != "github_ci" ||
            call.arguments.text("action") !in setOf("runs", "run", "jobs", "job", "logs", "failure", "status", "artifacts")
        ) {
            return call
        }
        if (!head.matches(Regex("[a-fA-F0-9]{40}"))) return null
        val target = repository.split('/')
        if (target.size != 2) return null
        val args = call.arguments
        if (listOf("owner" to target[0], "repo" to target[1]).any { (key, value) -> args.text(key).let { it.isNotBlank() && !it.equals(value, true) } } ||
            listOf("head_sha", "commit_sha", "expected_head_sha").any { args.text(it).let { sha -> sha.isNotBlank() && !sha.equals(head, true) } } ||
            !branch.isNullOrBlank() &&
            args.text("branch").let { it.isNotBlank() && it != branch } ||
            args.text("ref").let { it.isNotBlank() && it != branch && !it.equals(head, true) }
        ) {
            return null
        }
        val runId = args["run_id"]
        val jobId = args["job_id"]
        val observations = receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["success"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it.text("repository").equals(repository, true) &&
                it.text("tool") == call.name
        }.flatMap { receipt ->
            val root = dev.chungjungsoo.gptmobile.data.github.GitHubInventoryObservation.planningRoot(receipt) ?: return@flatMap emptyList()
            val runs = (root["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val jobs = (root["jobs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { row ->
                JsonObject(root.filterKeys { it in setOf("head_sha", "run_id", "run_attempt") } + row + ("job_id" to (row["id"] ?: kotlinx.serialization.json.JsonNull)))
            }
            runs + jobs + root
        }.filter { row ->
            if (jobId != null) row["job_id"] == jobId else runId != null && (row["run_id"] ?: row["id"]) == runId
        }
        val latest = observations.maxByOrNull { (it["run_attempt"] as? JsonPrimitive)?.intOrNull ?: 0 }
        if (latest != null &&
            (
                latest.text("head_sha").let { it.isNotBlank() && !it.equals(head, true) } ||
                    args["run_attempt"]?.let { attempt -> (attempt as? JsonPrimitive)?.intOrNull?.let { it < ((latest["run_attempt"] as? JsonPrimitive)?.intOrNull ?: 0) } } == true
                )
        ) {
            return null
        }
        val pinned = buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            put("owner", target[0])
            put("repo", target[1])
            if (args.text("action") == "runs") {
                put("head_sha", head)
            } else if (args.text("action") == "status" && runId == null && jobId == null) {
                put("commit_sha", head)
            } else {
                put("expected_head_sha", head)
                if (args["run_attempt"] == null) latest?.get("run_attempt")?.let { put("run_attempt", it) }
                if (runId == null) latest?.get("run_id")?.let { put("run_id", it) }
            }
        }
        return call.copy(arguments = pinned, originalInvocationFingerprint = call.originalInvocationFingerprint ?: GitHubEvidenceBook.digest("${call.callId}:${call.name}:${call.arguments}"))
    }

    /** Current-run telemetry tied to an authorized schema and one declared action. */
    data class RouteMeasurement(val samples: Int, val succeeded: Int, val latencyMillis: Long?) {
        val successPermille get() = if (samples > 0) (1000L * succeeded / samples).toInt() else 0
    }

    data class Decision(
        val action: Action,
        val reason: String,
        val focus: GitHubRequirementToolFocus.Focus? = null,
        val dependentCount: Int = 0,
        val deferredCount: Int = 0,
        val unavailableCount: Int = 0,
        val measurement: RouteMeasurement? = null
    ) {
        fun snapshot(mode: GitHubNextActionMode, legacy: GitHubRequirementToolFocus.Focus?): JsonObject = buildJsonObject {
            put("schema", "github.next-action.v1")
            put("mode", mode.name)
            put("action", action.name)
            put("reason", reason)
            put("dependent_criteria", dependentCount)
            measurement?.let {
                put("route_samples", it.samples)
                put("route_success_permille", it.successPermille)
                it.latencyMillis?.let { elapsed -> put("route_estimated_ms", elapsed) }
            }
            put("deferred_criteria", deferredCount)
            put("unavailable_candidates", unavailableCount)
            focus?.let {
                put("decision_key", it.identity)
                put("criterion_digest", GitHubEvidenceBook.digest(it.criterion))
                put("tool_families", JsonArray(it.operations.map(::JsonPrimitive)))
                put("expected_proof", it.stage.substringBefore(':'))
                if (it.actions.isNotEmpty()) put("selected_actions", JsonObject(it.actions.mapValues { entry -> JsonPrimitive(entry.value) }))
            }
            if (mode == GitHubNextActionMode.SHADOW) put("legacy_choice_differs", focus?.identity != legacy?.identity)
        }
    }

    private data class Candidate(
        val focus: GitHubRequirementToolFocus.Focus,
        val rank: Int,
        val unlocks: Int,
        val blocked: Boolean,
        val retainsEvidence: Boolean,
        val measurement: RouteMeasurement?
    )

    fun activeDecision(report: JsonObject?): JsonObject? = (report?.get("next_action_decision") as? JsonObject)?.takeIf {
        it["schema"] == JsonPrimitive("github.next-action.v1") && it["mode"] == JsonPrimitive(GitHubNextActionMode.ACTIVE.name)
    }

    fun selectedCriterion(rows: List<JsonObject>, decision: JsonObject?): JsonObject? {
        val digest = (decision?.get("criterion_digest") as? JsonPrimitive)?.content ?: return null
        return rows.firstOrNull { row -> GitHubEvidenceBook.digest(row.text("id")) == digest }
    }

    fun decide(
        report: JsonObject,
        measured: (String, String) -> RouteMeasurement? = { _, _ -> null },
        available: (String, String?) -> Boolean
    ): Decision {
        val ledger = report["execution_ledger"] as? JsonObject
        val recovery = ledger.text("recovery")
        if (recovery == "RECONCILE_REMOTE_OUTCOME" || (ledger?.get("unresolved_remote_outcomes") as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true) {
            val graph = ledger?.get("pending_graph_index") is JsonObject
            val operation = if (graph) "repository_graph" else "github_context"
            val action = if (graph) "status" else "recovery"
            val focus = GitHubRequirementToolFocus.Focus(
                GitHubEvidenceBook.digest("reconcile|$operation|$action"),
                if (graph) "Read the existing index status before resuming it." else "Read the saved remote operation and reconcile its exact outcome.",
                listOf(operation),
                mapOf(operation to action),
                "uncertain-operation",
                "REMOTE_OUTCOME"
            )
            return Decision(Action.RECONCILE, "UNKNOWN_REMOTE_OUTCOME", focus.takeIf { available(operation, action) })
        }
        if (report["completion_verified"] == JsonPrimitive(true)) return Decision(Action.VERIFY, "DELIVER_VERIFIED_RESULT")
        when (recovery) {
            "AUTHORIZE_NEW_TIME_WINDOW", "RESTORE_AUTHORIZED_CONFIGURATION", "RECONCILE_FIXTURE_AFTER_APPROVAL_REJECTION", "INSPECT_VERIFICATION_FAILURE",
            "INSPECT_EXECUTION_FAILURE", "USER_INPUT_REQUIRED", "RESUME_VERIFICATION" ->
                return Decision(if (recovery == "RESUME_VERIFICATION") Action.VERIFY else Action.WAIT, recovery)
        }
        val budget = (report["stage_budget"] ?: (report["test_checklist"] as? JsonObject)?.get("stage_budget")) as? JsonObject
        if (budget?.get("acquisition_allowed") == JsonPrimitive(false)) return Decision(Action.VERIFY, "PRESERVE_VERIFICATION_RESERVE")

        val rows = GitHubAcceptanceWork.outstanding(report)
        val dependencies = rows.flatMap { row -> (row["pending_dependencies"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().map { it.content }.distinct() }
            .groupingBy { it }.eachCount()
        val waitingForCi = report["observation_state"] == JsonPrimitive("WAITING_FOR_CI")
        var unavailable = 0
        val deferred = rows.count(GitHubAcceptanceWork::deferred)
        val candidates = rows.filterNot(GitHubAcceptanceWork::deferred).mapNotNull { row ->
            val focus = GitHubRequirementToolFocus.fromRows(listOf(row)) ?: return@mapNotNull null
            val linked = (row["eligible_receipt_count"] as? JsonPrimitive)?.intOrNull ?: 0
            val reuse = linked > 0 && row.text("acceptance_mismatch") in setOf("NO_MATCHING_CLAIMS", "ACCEPTANCE_MAPPING_REQUIRED", "UNSUPPORTED_CLAIMS", "LINKED_RECEIPT_INELIGIBLE")
            val independentRead = focus.stage == "DIAGNOSIS" ||
                focus.operations.isNotEmpty() &&
                focus.operations.all { it in setOf("github_search", "github_read") } ||
                (row["criterion_contract"] as? JsonObject).text("evidence_producer") in setOf("SOURCE_OBSERVATION", "DEFECT_ANALYSIS")
            if (waitingForCi && !reuse && !independentRead) return@mapNotNull null
            val routes = if (reuse) emptyList() else focus.operations.filter { available(it, focus.actions[it]) }
            if (!reuse && focus.operations.isNotEmpty() && routes.isEmpty()) {
                unavailable++
                return@mapNotNull null
            }
            val unlocks = dependencies[focus.criterion] ?: 0
            val rank = when {
                reuse -> 0
                focus.stage == "ISSUE_INVENTORY" || unlocks > 0 -> 1
                focus.stage in setOf("DIAGNOSIS", "CHANGE", "TEST", "CLOSURE") -> 2
                focus.actions.any { (tool, action) -> GitHubAuthorizationBoundary.isReplayableRead(tool, buildJsonObject { put("action", action) }) } -> 3
                else -> 4
            }
            val phase = (row["issue_phases"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull { it.text("stage") == focus.stage }
            val blocked = listOfNotNull(row, phase).any { it.text("state").ifBlank { it.text("acceptance_state").ifBlank { it.text("status") } } == "BLOCKED" }
            val selected = if (reuse) {
                focus.copy(identity = GitHubEvidenceBook.digest(focus.identity + "|reuse"), query = "Verify the observations already linked to this criterion.", operations = emptyList(), actions = emptyMap(), stage = "LINKED_EVIDENCE")
            } else {
                focus.copy(operations = routes, actions = focus.actions.filterKeys { it in routes })
            }
            val exact = selected.actions.entries.singleOrNull()
            val measuredRoute = exact?.let { measured(it.key, it.value) }?.takeIf { it.samples >= 3 }
            Candidate(selected, rank, unlocks, blocked, reuse, measuredRoute)
        }
        val next = candidates.minWithOrNull(
            compareBy<Candidate> { it.blocked }.thenBy { it.rank }.thenByDescending { it.unlocks }
                .thenBy {
                    when {
                        it.measurement == null -> 1
                        it.measurement.successPermille >= 800 -> 0
                        else -> 2
                    }
                }
                .thenByDescending { it.measurement?.successPermille }
                .thenBy { it.measurement?.latencyMillis ?: Long.MAX_VALUE }
                .thenBy { it.focus.criterion }
        )
            ?: return when {
                waitingForCi -> Decision(Action.WAIT, "WAIT_FOR_BOUND_CI_RUN", deferredCount = deferred, unavailableCount = unavailable)
                unavailable > 0 -> Decision(Action.DISCOVER, "AUTHORIZED_CAPABILITY_NOT_LOADED", deferredCount = deferred, unavailableCount = unavailable)
                recovery == "WAITING_FOR_APPROVAL" || deferred > 0 -> Decision(Action.WAIT, "AWAIT_CRITERION_PREREQUISITE", deferredCount = deferred)
                else -> Decision(Action.VERIFY, "CHECK_RETAINED_EVIDENCE")
            }
        val action = when {
            next.retainsEvidence -> Action.REUSE_EVIDENCE
            next.focus.stage == "CATALOG" -> Action.DISCOVER
            next.focus.operations.isEmpty() -> Action.VERIFY
            else -> Action.ACQUIRE
        }
        val reason = when {
            waitingForCi -> "INDEPENDENT_DURING_CI"
            next.unlocks > 0 -> "UNBLOCKS_DEPENDENTS"
            else -> next.focus.stage.substringBefore(':')
        }
        return Decision(action, reason, next.focus, next.unlocks, deferred, unavailable, next.measurement)
    }

    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.content.orEmpty()
}
