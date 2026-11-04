package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Answer truth, request coverage and engineering acceptance are independent dimensions. */
internal object GitHubRequestCompletion {
    private val question = Regex("(?i)^(?:please\\s+)?(?:what|where|which|who|why|how|when|is|are|does|do|can|could|would|should|will|has|have|explain|describe|show|list|summarize|compare|review|diagnose|inspect|examine|analy[sz]e|find|read|look\\s+at|tell\\s+me)\\b")
    private val mutation = Regex("(?im)^(?:\\s*[-*\\d.)]+\\s*)?(?:please\\s+)?(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?(?:add|implement|fix|repair|build|create|merge|publish|push|delete|remove|change|update|run|test|deploy|install|cancel|rerun|continue|resume)\\b")
    fun informational(task: String): Boolean {
        val request = GitHubTaskContinuity.request(task).replaceFirst(Regex("(?i)^(?:please[, ]+|i (?:want|need|would like) you to |can you |could you )+"), "").trim()
        return GitHubStatusRequest.matches(request) ||
            !GitHubTaskCoverage.matches(request) &&
            !mutation.containsMatchIn(request) &&
            (question.containsMatchIn(request) || Regex("(?i)^(?:give|provide|offer|perform|conduct) (?:me |a |an |the )*(?:full |detailed |in-depth )*(?:analysis|review|explanation|solutions|recommendations|report)\\b").containsMatchIn(request))
    }

    fun requirements(task: String): List<String> = task.lineSequence().map(String::trim).filter { it.isNotBlank() && it !in setOf("Acceptance criteria:", "Request details:") && !it.startsWith("Budget:") && !it.startsWith("[github-workflow-preset:") }.map { it.removePrefix("- ") }.toList().ifEmpty { listOf("Requested answer") }
    fun id(requirement: String): String = GitHubEvidenceBook.digest(requirement).take(24)

    private fun latestAttempts(receipts: Map<String, JsonObject>): Map<String, Int> = buildMap {
        fun observe(data: JsonObject) {
            val run = (data["run_id"] as? JsonPrimitive)?.contentOrNull
                ?: (data["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { data.containsKey("run_attempt") }
                ?: return
            val attempt = listOf("run_attempt", "expected_run_attempt").mapNotNull { (data[it] as? JsonPrimitive)?.intOrNull }.maxOrNull() ?: return
            if (attempt > 0) put(run, maxOf(get(run) ?: 0, attempt))
        }
        // Inspect every trusted observation, including accepted reruns, rather than
        // letting an answer select only the receipts from a superseded success.
        receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["success"] == JsonPrimitive(true) && (it["tool"] as? JsonPrimitive)?.contentOrNull?.substringBefore("__") == "github_ci" }.forEach { receipt ->
            val result = receipt["result"] as? JsonObject ?: return@forEach
            val data = (result["data"] as? JsonObject) ?: result
            observe(data)
            (data["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach(::observe)
        }
    }

    private data class Assessment(val state: GitHubAcceptanceLedger.State, val reason: String)

    private fun engineeringAcceptance(acceptance: JsonObject, receipts: Map<String, JsonObject>): Assessment {
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun selected(key: String) = (acceptance[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(receipts::get) }
        fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        fun eligible(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) && receipt["verification_eligible"] != JsonPrimitive(false) && receipt["success"] == JsonPrimitive(true)
        val implementations = selected("implementation_receipts").filter { receipt ->
            eligible(receipt) &&
                (receipt["arguments"] as? JsonObject)?.text("action") in setOf("commit", "commit_staged_patch") &&
                receipt.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}"))
        }
        if (implementations.isEmpty()) return Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "No authorized published implementation receipt is linked to this requirement")
        val attempts = latestAttempts(receipts)
        val latestHead = receipts.values.lastOrNull { eligible(it) && it["head_observation"] == JsonPrimitive(true) && it.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}")) }?.get("source_head_sha")
        if (implementations.any { latestHead != null && latestHead != it["source_head_sha"] }) {
            return Assessment(GitHubAcceptanceLedger.State.BLOCKED, "Implementation or test evidence belongs to a superseded repository revision")
        }
        val assessments = implementations.map implementation@{ implementation ->
            fun matching(test: JsonObject): Boolean = eligible(test) &&
                test.text("tool").substringBefore("__") == "github_ci" &&
                (test["arguments"] as? JsonObject)?.text("action") == "jobs" &&
                root(test)?.get("head_sha") == implementation["source_head_sha"]
            val selectedPages = selected("test_receipts").filter(::matching).mapNotNull(::root)
            val runs = selectedPages.groupBy { it["run_id"] to it["run_attempt"] }
            if (runs.isEmpty()) return@implementation Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "No test jobs are linked to the published implementation revision")
            val runStates = runs.map run@{ (identity, _) ->
                val run = (identity.first as? JsonPrimitive)?.contentOrNull
                val attempt = (identity.second as? JsonPrimitive)?.intOrNull
                if (run == null || attempt == null || attempt <= 0 || attempt != attempts[run]) {
                    return@run Assessment(GitHubAcceptanceLedger.State.BLOCKED, "A newer workflow attempt exists or the selected attempt is not identified")
                }
                // A selected old page cannot hide a subsequently observed failure in
                // the same run/attempt. The latest observation of every page wins.
                val pages = receipts.values.filter(::matching).mapNotNull(::root).filter { it["run_id"] == identity.first && it["run_attempt"] == identity.second }
                val jobs = GitHubWorkflowJobEvidence.completeJobs(pages)
                    ?: return@run Assessment(GitHubAcceptanceLedger.State.BLOCKED, "Workflow job inventory is incomplete; every page is required")
                if (jobs.any { it.text("conclusion") in setOf("failure", "cancelled", "timed_out", "action_required", "startup_failure", "stale") }) {
                    return@run Assessment(GitHubAcceptanceLedger.State.FAIL, "The selected workflow has a failed, cancelled or otherwise unsuccessful job at this revision and attempt")
                }
                val tests = jobs.filter { Regex("(?i)\\b(?:tests?|unit|instrumentation)\\b").containsMatchIn(it.text("name")) }
                if (tests.isEmpty()) return@run Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "No executed test job was identified in the workflow")
                if (jobs.any { it.text("status") != "completed" } || tests.any { it.text("conclusion") != "success" }) {
                    return@run Assessment(GitHubAcceptanceLedger.State.BLOCKED, "Test execution or another workflow job remains incomplete; skipped tests are not passing tests")
                }
                Assessment(GitHubAcceptanceLedger.State.PASS, "Published implementation and successful test jobs at the exact revision and latest attempt")
            }
            runStates.firstOrNull { it.state == GitHubAcceptanceLedger.State.FAIL }
                ?: runStates.firstOrNull { it.state != GitHubAcceptanceLedger.State.PASS }
                ?: runStates.first()
        }
        return assessments.firstOrNull { it.state == GitHubAcceptanceLedger.State.FAIL }
            ?: assessments.firstOrNull { it.state != GitHubAcceptanceLedger.State.PASS }
            ?: assessments.first()
    }

    fun evaluate(task: String, claims: List<JsonObject>, answer: JsonObject, receipts: Map<String, JsonObject>): JsonObject {
        val verified = claims.isNotEmpty() && claims.all { it["status"] in setOf(JsonPrimitive("VERIFIED"), JsonPrimitive("INFERRED")) }
        val requirements = requirements(task)
        val coverage = (answer["requirement_claims"] as? JsonObject).orEmpty()
        val unanswered = (answer["questions"] as? JsonArray).orEmpty().isNotEmpty()
        val rows = requirements.map { requirement ->
            val selected = (coverage[id(requirement)] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val covered = if (requirements.size == 1) verified else selected.isNotEmpty() && selected.all { claims.getOrNull(it)?.get("status") in setOf(JsonPrimitive("VERIFIED"), JsonPrimitive("INFERRED")) }
            val acceptance = (answer["acceptance"] as? JsonObject)?.get(id(requirement)) as? JsonObject
            val assessment = acceptance?.let { engineeringAcceptance(it, receipts) }
            val implementedAndTested = assessment?.state == GitHubAcceptanceLedger.State.PASS
            val passed = covered && !unanswered && (informational(task) || implementedAndTested)
            val state = when {
                passed -> GitHubAcceptanceLedger.State.PASS
                unanswered -> GitHubAcceptanceLedger.State.BLOCKED
                informational(task) -> if (receipts.isEmpty()) GitHubAcceptanceLedger.State.NOT_TESTED else GitHubAcceptanceLedger.State.BLOCKED
                assessment?.state == GitHubAcceptanceLedger.State.PASS -> GitHubAcceptanceLedger.State.BLOCKED
                else -> assessment?.state ?: GitHubAcceptanceLedger.State.NOT_TESTED
            }
            buildJsonObject {
                put("id", id(requirement))
                put("requirement", requirement)
                put(
                    "status",
                    when (state) {
                        GitHubAcceptanceLedger.State.PASS -> "PASSED"
                        GitHubAcceptanceLedger.State.FAIL -> "FAILED"
                        GitHubAcceptanceLedger.State.BLOCKED -> "BLOCKED"
                        GitHubAcceptanceLedger.State.NOT_TESTED -> if (receipts.isEmpty()) "PENDING" else "UNTESTED"
                    }
                )
                put("acceptance_state", state.name)
                put(
                    "acceptance",
                    if (unanswered) {
                        "A user decision remains unresolved"
                    } else if (informational(task) && passed) {
                        "Receipt-supported answer; no implementation or test execution inferred"
                    } else if (!covered && implementedAndTested) {
                        "Matching test evidence is present, but answer coverage for this requirement remains unverified"
                    } else {
                        assessment?.reason ?: "Requires explicit acceptance evidence for this requirement"
                    }
                )
                acceptance?.get("implementation_receipts")?.let { put("implementation_receipts", it) }
                acceptance?.get("test_receipts")?.let { put("test_receipts", it) }
            }
        }
        val complete = rows.all { it["status"] == JsonPrimitive("PASSED") }
        return buildJsonObject {
            put("answer_verified", verified)
            put("completion_verified", complete)
            put("task_outcome", if (complete) "COMPLETED" else "PARTIAL")
            put("requirements", JsonArray(rows))
            put("remaining_requirements", JsonArray(rows.filter { it["status"] != JsonPrimitive("PASSED") }))
            put("completion_scope", if (informational(task)) "Answer coverage" else "Engineering acceptance requires implementation and revision-bound test evidence")
        }
    }
}
