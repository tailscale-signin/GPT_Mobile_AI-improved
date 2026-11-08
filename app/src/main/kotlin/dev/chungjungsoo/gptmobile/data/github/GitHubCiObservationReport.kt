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

    fun render(task: String, receipts: Map<String, JsonObject>, now: Long, epoch: Long): Pair<String, JsonObject>? {
        if (!matches(task)) return null
        val eligible = receipts.filterValues { receipt ->
            val observed = (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                observed in 1..now &&
                (receipt["epoch"] == JsonPrimitive(epoch) || receipt["immutable"] == JsonPrimitive(true))
        }
        val head = eligible.values.lastOrNull { it["head_observation"] == JsonPrimitive(true) }
            ?.text("source_head_sha")?.takeIf(sha::matches) ?: return null
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
        val observed = GitHubObservedWorkflowRuns.current(eligible.values, JsonPrimitive(head))
        if (observed.isEmpty() || observed.any { it.text("status") != "completed" || it.text("conclusion") !in setOf("success", "skipped", "neutral") || (it["run_attempt"] as? JsonPrimitive)?.intOrNull?.let { attempt -> attempt > 0 } != true }) return null
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
            put("successful_run_count", successful)
            put("skipped_run_count", skipped)
            put("neutral_run_count", neutral)
            put("failed_run_count", 0)
            put("full_history_verified", false)
            put("build_health_verified", false)
            put("new_test_execution_verified", false)
            put("requirements", requirements)
            put("remaining_requirements", JsonArray(emptyList()))
            put("evidence_receipt_ids", evidenceIds)
            put("verified_at_ms", now)
        }
        val text = "# CI investigation\n\nNo failed runs were observed in the returned set for commit `$head`.\n\n" +
            "| Returned workflow result | Runs |\n| --- | ---: |\n| Successful | $successful |\n| Skipped | $skipped |\n| Neutral | $neutral |\n| Failed | 0 |\n\n" +
            "All ${observed.size} observed runs for this revision were completed. Failure-specific job and log investigation, root-cause attribution and code repairs are inapplicable to this returned set.\n\n" +
            "This is a bounded finding from saved receipts. It does not establish that older or unreturned attempts have no failures, that skipped jobs executed, or that all required checks passed. No new tests were run. A particular older failed attempt needs its own run and attempt identity."
        return text to report
    }

    private fun JsonObject.data(): JsonObject? = (get("result") as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private val unresolvedCheckStates = setOf("failure", "failed", "error", "pending", "running", "queued", "in_progress", "waiting", "requested", "unknown")
}
