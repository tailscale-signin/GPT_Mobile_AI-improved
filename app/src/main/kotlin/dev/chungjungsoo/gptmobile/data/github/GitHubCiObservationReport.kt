package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A bounded CI diagnosis can finish without inventing a failed job, repair or new test execution. */
internal object GitHubCiObservationReport {
    private val sha = Regex("[a-fA-F0-9]{40}")
    private val simpleDiagnosis = Regex("(?i)^(?:please\\s+)?(?:diagnose|investigate|inspect|check)\\s+(?:CI|workflow)\\s+(?:failures?|errors?)(?:\\s+on\\s+the\\s+(?:selected|current)\\s+(?:revision|branch))?[.!?]?$")

    fun matches(task: String): Boolean {
        val requirements = GitHubRequestCompletion.requirements(task)
        val preset = GitHubWorkflowPreset.selected("diagnose_ci")
        return requirements == GitHubRequestCompletion.requirements(preset.prompt()) ||
            requirements.size == 1 &&
            simpleDiagnosis.matches(requirements.single())
    }

    fun render(task: String, receipts: Map<String, JsonObject>, now: Long, epoch: Long): Pair<String, JsonObject>? =
        render(task, receipts, now, epoch, checkSnapshotOnly = false)

    fun render(task: String, receipts: Map<String, JsonObject>, now: Long, epoch: Long, checkSnapshotOnly: Boolean): Pair<String, JsonObject>? {
        if (!matches(task)) return null
        val eligible = GitHubStatusEvidence.selected(receipts, null).filterValues { receipt ->
            val observed = (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                observed in 1..now &&
                (GitHubStatusEvidence.fresh(receipt, now, epoch) || receipt["immutable"] == JsonPrimitive(true))
        }
        val head = eligible.values.lastOrNull { it["head_observation"] == JsonPrimitive(true) }
            ?.text("source_head_sha")?.takeIf(sha::matches) ?: return null
        val observed = GitHubObservedWorkflowRuns.current(eligible.values, JsonPrimitive(head))
        val checkReceipt = eligible.entries.lastOrNull { (_, row) ->
            GitHubStatusEvidence.fresh(row, now, epoch) &&
                (GitHubStatusEvidence.operation(row, "github_ci", "status") || GitHubStatusEvidence.operation(row, "github_context", "checks")) &&
                (row.text("source_head_sha") == head || (row["arguments"] as? JsonObject)?.text("commit_sha") == head)
        }
        if (checkReceipt != null) {
            val inventory = if (GitHubStatusEvidence.operation(checkReceipt.value, "github_ci", "status")) {
                checkSnapshot(checkReceipt.key, checkReceipt.value, head)
            } else {
                GitHubStatusEvidence.checks(eligible, head, now, epoch).takeIf { it.complete }
                    ?: checkSnapshot(checkReceipt.key, checkReceipt.value, head)
            }
            if (checkSnapshotOnly && inventory?.complete == true && inventory.outcomes.isNotEmpty()) {
                val evidence = JsonArray(inventory.receiptIds.map(::JsonPrimitive))
                val requirements = JsonArray(
                    GitHubRequestCompletion.requirements(task).map { requirement ->
                        buildJsonObject {
                            put("id", GitHubRequestCompletion.id(requirement))
                            put("requirement", requirement)
                            put("status", "PENDING")
                            put("acceptance_state", "NOT_TESTED")
                        }
                    }
                )
                val counts = inventory.outcomes.entries.joinToString(", ") { (state, count) -> "$count $state" }
                return "The retained check snapshot for `${head.take(12)}` shows $counts. The workflow diagnosis is incomplete." to buildJsonObject {
                    put("schema", "github.verification.v1")
                    put("task_outcome", "PARTIAL")
                    put("observation_state", "CHECK_SNAPSHOT_ONLY")
                    put("partial", true)
                    put("answer_verified", true)
                    put("claims_verified", true)
                    put("completion_verified", false)
                    put("head_sha", head)
                    put("check_inventory", inventory.row())
                    put("evidence_receipt_ids", evidence)
                    put("requirements", requirements)
                    put("remaining_requirements", requirements)
                    put("verified_at_ms", now)
                }
            }
            inventory?.let { pendingObservation(task, head, it, observed, eligible, now, epoch) }?.let { return it }
        }
        if (checkSnapshotOnly) return null
        val pages = eligible.filterValues { receipt ->
            receipt.text("tool").substringBefore("__") == "github_ci" &&
                (receipt["arguments"] as? JsonObject)?.text("action") == "runs"
        }
        if (pages.isEmpty()) return null
        // Inspect complete retained pages, never a truncated model/display projection.
        if (pages.values.any { receipt ->
                val root = receipt.data() ?: return@any true
                val runs = root["workflow_runs"] as? JsonArray ?: return@any true
                val returned = (root["returned"] as? JsonPrimitive)?.intOrNull ?: return@any true
                returned != runs.size ||
                    runs.any { value ->
                        value !is JsonObject || (value["id"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true || !sha.matches(value.text("head_sha"))
                    } ||
                    root["pagination_inconsistent"] == JsonPrimitive(true)
            }
        ) {
            return null
        }
        val completeEmptyFailureQuery = pages.values.any { receipt ->
            val arguments = receipt["arguments"] as? JsonObject ?: return@any false
            val data = receipt.data() ?: return@any false
            GitHubStatusEvidence.fresh(receipt, now, epoch) && GitHubCiFailureObservation.emptyRevision(arguments, data) == head
        }
        if (observed.isEmpty() && !completeEmptyFailureQuery || observed.any { it.text("status") != "completed" || it.text("conclusion") !in setOf("success", "skipped", "neutral") || (it["run_attempt"] as? JsonPrimitive)?.intOrNull?.let { attempt -> attempt > 0 } != true }) return null
        // A separate failed/pending check observation must not disappear just because
        // the returned workflow list contains only successful and skipped runs.
        if (eligible.values.any { receipt ->
                if (receipt.text("source_head_sha") != head) return@any false
                val data = receipt.data() ?: return@any false
                val checkData = (data["check_details"] as? JsonObject) ?: data
                val checks = checkData["check_runs"] as? JsonArray
                val legacy = data["status_details"] as? JsonObject
                val legacyCount = (legacy?.get("total_count") as? JsonPrimitive)?.intOrNull
                (
                    checks != null &&
                        checks.any checkRow@{ value ->
                            val check = value as? JsonObject ?: return@checkRow true
                            check.text("status") != "completed" || check.text("conclusion") !in setOf("success", "skipped", "neutral")
                        }
                    ) ||
                    data.text("state").lowercase() in unresolvedCheckStates ||
                    data.text("checks").lowercase() in unresolvedCheckStates ||
                    legacy != null &&
                    (legacyCount == null || legacyCount < 0 || legacyCount > 0 && legacy.text("state") != "success")
            }
        ) {
            return null
        }
        val successful = observed.count { it.text("conclusion") == "success" }
        val skipped = observed.count { it.text("conclusion") == "skipped" }
        val neutral = observed.count { it.text("conclusion") == "neutral" }
        val evidenceIds = JsonArray((pages.keys + eligible.filterValues { it.text("source_head_sha") == head }.keys).distinct().map(::JsonPrimitive))
        val requirements = JsonArray(
            GitHubRequestCompletion.requirements(task).mapIndexed { index, requirement ->
                buildJsonObject {
                    put("id", GitHubRequestCompletion.id(requirement))
                    put("requirement", requirement)
                    put("status", if (index == 0) "PASSED" else "NOT_APPLICABLE")
                    put("acceptance_state", if (index == 0) "PASS" else "NOT_TESTED")
                    put("required", index == 0)
                    put("acceptance", if (index == 0) "No failed runs were observed in the returned set for the selected revision." else "Failure-specific job/log diagnosis and repairs are inapplicable to this observed set because no failure was returned; skipped runs are not passing tests.")
                    put("evidence_receipt_ids", evidenceIds)
                }
            }
        )
        val report = buildJsonObject {
            put("schema", "github.ci-observation.v1")
            put("task", task)
            put("task_outcome", "COMPLETED")
            put("completion_verified", true)
            put("answer_verified", true)
            put("claims_verified", true)
            put("partial", false)
            put("resume_required", false)
            put("completion_scope", "NO_FAILURES_OBSERVED_IN_RETURNED_SET_FOR_SELECTED_REVISION")
            put("head_sha", head)
            put("returned_run_count", observed.size)
            if (observed.isNotEmpty()) {
                put("successful_run_count", successful)
                put("skipped_run_count", skipped)
                put("neutral_run_count", neutral)
            }
            put("failure_query_complete", completeEmptyFailureQuery)
            put("failed_run_count", 0)
            put("full_history_verified", false)
            put("build_health_verified", false)
            put("new_test_execution_verified", false)
            put("requirements", requirements)
            put("remaining_requirements", JsonArray(emptyList()))
            put("evidence_receipt_ids", evidenceIds)
            put("verified_at_ms", now)
        }
        val text = if (observed.isEmpty()) {
            "# CI investigation\n\nThe complete failure query returned no failed workflow runs for commit `$head`.\n\n" +
                "There is no failed run in this captured query to diagnose. This does not establish that every required check passed, that skipped jobs executed, or that older revisions have no failures. No new tests were run."
        } else {
            "# CI investigation\n\nNo failed runs were observed in the returned set for commit `$head`.\n\n" +
                "| Returned workflow result | Runs |\n| --- | ---: |\n| Successful | $successful |\n| Skipped | $skipped |\n| Neutral | $neutral |\n| Failed | 0 |\n\n" +
                "All ${observed.size} observed runs for this revision were completed. Failure-specific job and log investigation, root-cause attribution and code repairs are inapplicable to this returned set.\n\n" +
                "This is a bounded finding from saved receipts. It does not establish that older or unreturned attempts have no failures, that skipped jobs executed, or that all required checks passed. No new tests were run. A particular older failed attempt needs its own run and attempt identity."
        }
        return text to report
    }

    private fun checkSnapshot(id: String, receipt: JsonObject, head: String): GitHubStatusEvidence.CheckInventory? {
        // The executor records this summary before paging a large result for the model.
        val summary = receipt["semantic_summary"] as? JsonObject ?: return null
        if (summary.text("schema") != "github.semantic-summary.v1" ||
            summary.text("commit_sha") != head ||
            summary.text("operation").githubOperationFamily() to summary.text("action") !in setOf("github_ci" to "status", "github_context" to "checks") ||
            (receipt["arguments"] as? JsonObject)?.text("commit_sha").orEmpty().let { it.isNotBlank() && it != head } ||
            summary["check_inventory_complete"] != JsonPrimitive(true) ||
            summary.text("observed_check_state") != "RUNNING"
        ) {
            return null
        }
        val counts = summary["check_counts"] as? JsonObject ?: return null
        val outcomes = linkedMapOf<String, Int>()
        for ((key, label) in listOf("successful" to "success", "skipped" to "skipped", "in_progress" to "running", "other_completed" to "other", "failed" to "failure")) {
            val count = (counts[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
            if (count > 0) outcomes[label] = count
        }
        val total = (summary["observed_checks"] as? JsonPrimitive)?.intOrNull ?: return null
        if (summary["reported_total_checks"] != JsonPrimitive(total) || outcomes.values.sumOf(Int::toLong) != total.toLong()) return null
        val statuses = (summary["observed_statuses"] as? JsonPrimitive)?.intOrNull ?: return null
        if (statuses != 0 || summary["reported_total_statuses"] != JsonPrimitive(0)) return null
        return GitHubStatusEvidence.CheckInventory(setOf(id), total, total, statuses, statuses, complete = true, outcomes = outcomes)
    }

    /** Running checks are a pending outcome; an unavailable-log probe is not evidence we need to acquire. */
    private fun pendingObservation(
        task: String,
        head: String,
        inventory: GitHubStatusEvidence.CheckInventory,
        runs: List<JsonObject>,
        receipts: Map<String, JsonObject>,
        now: Long,
        epoch: Long
    ): Pair<String, JsonObject>? {
        if (!inventory.complete ||
            inventory.observedStatuses != 0 ||
            inventory.outcomes.getOrDefault("running", 0) == 0 ||
            inventory.outcomes.keys.any { it !in setOf("running", "success", "neutral", "skipped") }
        ) {
            return null
        }
        val activeStates = setOf("in_progress", "queued", "waiting", "requested", "pending")
        if (runs.any { if (it.text("status") == "completed") it.text("conclusion") !in setOf("success", "neutral", "skipped") else it.text("status") !in activeStates }) return null
        val fresh = receipts.filterValues { GitHubStatusEvidence.fresh(it, now, epoch) }
        val active = GitHubObservedWorkflowRuns.current(fresh.values, JsonPrimitive(head)).filter { row ->
            row.text("status") in activeStates && (row["run_attempt"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true
        }
        // Preserve an already observed pending-log join, but do not issue a log
        // request just to establish that checks at this exact SHA are running.
        val logs = fresh.entries.lastOrNull { (_, receipt) ->
            if (!GitHubStatusEvidence.operation(receipt, "github_ci", "logs")) return@lastOrNull false
            val data = receipt.data() ?: return@lastOrNull false
            val args = receipt["arguments"] as? JsonObject ?: return@lastOrNull false
            val run = active.singleOrNull { it["id"] == data["run_id"] } ?: return@lastOrNull false
            (data["job_id"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true &&
                data.text("status") == "PENDING_LOG_AVAILABILITY" &&
                data["logs_available"] == JsonPrimitive(false) &&
                data.text("head_sha") == head &&
                data.text("job_status") in activeStates &&
                args["job_id"] == data["job_id"] &&
                (args["run_id"] == null || args["run_id"] == run["id"]) &&
                (args["run_attempt"] == null || args["run_attempt"] == run["run_attempt"])
        }
        val run = active.firstOrNull { it["id"] == logs?.value?.data()?.get("run_id") } ?: active.firstOrNull()
        val runId = run?.get("id")
        val attempt = run?.get("run_attempt")
        val jobId = logs?.value?.data()?.get("job_id")
        val runSources = fresh.filterValues { row ->
            run != null &&
                row.text("tool").substringBefore("__") == "github_ci" &&
                GitHubInventoryObservation.planningRoot(row)?.let { value ->
                    value == run || (value["workflow_runs"] as? JsonArray).orEmpty().contains(run)
                } == true
        }
        val counts = inventory.outcomes
        val evidence = JsonArray(
            receipts.filter { (id, row) ->
                id in inventory.receiptIds ||
                    id == logs?.key ||
                    id in runSources ||
                    row["head_observation"] == JsonPrimitive(true) &&
                    row.text("source_head_sha") == head
            }.keys.map(::JsonPrimitive)
        )
        val requested = GitHubRequestCompletion.requirements(task)
        val requirements = JsonArray(
            requested.mapIndexed { index, requirement ->
                val observed = index == 0 && requested.size > 1 && run != null
                buildJsonObject {
                    put("id", GitHubRequestCompletion.id(requirement))
                    put("requirement", requirement)
                    put("status", if (observed) "PASSED" else "PENDING")
                    put("acceptance_state", if (observed) "PASS" else "NOT_TESTED")
                    put("evidence_receipt_ids", evidence)
                }
            }
        )
        val neutral = counts["neutral"]?.let { ", $it neutral" }.orEmpty()
        val watchName = runSources.values.lastOrNull()?.text("tool")?.let { "github_watch_checks" + it.substringAfter("github_ci") }
        val inventoryTool = inventory.receiptIds.firstNotNullOfOrNull { fresh[it]?.text("tool") }
        val runLookup = inventoryTool?.takeIf { it.substringBefore("__") in setOf("github_ci", "github_context") }?.let {
            "github_ci" + it.removePrefix(it.substringBefore("__"))
        }
        val subscription = receipts.entries.lastOrNull { (_, row) ->
            val result = row.data()
            watchName != null &&
                row.text("tool") == watchName &&
                result?.get("background_subscription") == JsonPrimitive(true) &&
                result["all_checks"] == JsonPrimitive(true) &&
                result.text("head_sha") == head &&
                result.text("subscription_id").isNotEmpty()
        }
        val text = "Of ${inventory.observedChecks} observed checks, ${counts.getOrDefault("success", 0)} succeeded, " +
            "${counts.getOrDefault("skipped", 0)} ${if (counts["skipped"] == 1) "was" else "were"} skipped$neutral and ${counts.getValue("running")} ${if (counts["running"] == 1) "was" else "were"} still running. No failing checks were returned.\n\n" +
            (if (run != null) "Workflow run `$runId`, attempt $attempt, is still active at `${head.take(12)}`. " else "The captured checks at `${head.take(12)}` are still active. ") +
            (if (jobId != null) "Logs for job `$jobId` are pending. " else "") +
            "The workflow outcome remains open; no passing build or new test execution is established." +
            when {
                subscription != null -> "\n\nBackground monitoring is registered for all checks at this commit. Updates will appear in its check card."
                runId == null -> "\n\nA workflow run and attempt at this revision have yet to be identified. Background monitoring requires that match. Refresh the checks at this commit to continue."
                else -> "\n\nBackground monitoring has no confirmed subscription. Reopen the check at this commit to register or refresh it under the current GitHub connection."
            }
        return text to buildJsonObject {
            put("schema", "github.ci-observation.v1")
            put("task", task)
            put("task_outcome", "PARTIAL")
            put("observation_state", "WAITING_FOR_CI")
            put("completion_verified", false)
            put("answer_verified", true)
            put("claims_verified", true)
            put("partial", true)
            put("head_sha", head)
            runId?.let { put("run_id", it) }
            attempt?.let { put("run_attempt", it) }
            jobId?.let { put("pending_job_id", it) }
            watchName?.let { put("watch_tool", it) }
            if (runId == null) runLookup?.let { put("run_lookup_tool", it) }
            put("background_monitoring_registered", subscription != null)
            subscription?.let { put("watch_receipt_id", it.key) }
            if (logs != null) put("logs_available", false)
            put("build_health_verified", false)
            put("new_test_execution_verified", false)
            put("check_inventory", inventory.row())
            put("requirements", requirements)
            put("remaining_requirements", JsonArray(requirements.filter { (it as JsonObject)["status"] == JsonPrimitive("PENDING") }))
            put("evidence_receipt_ids", evidence)
            put("verified_at_ms", now)
        }
    }

    private fun JsonObject.data(): JsonObject? = (get("result") as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private val unresolvedCheckStates = setOf("failure", "failed", "error", "pending", "running", "queued", "in_progress", "waiting", "requested", "unknown")
}
