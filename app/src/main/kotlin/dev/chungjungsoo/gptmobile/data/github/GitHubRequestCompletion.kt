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
    fun informational(task: String): Boolean = question.containsMatchIn(task.trim()) && !mutation.containsMatchIn(task) && !GitHubTaskCoverage.matches(task)

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

    private fun engineeringAcceptance(acceptance: JsonObject, receipts: Map<String, JsonObject>): Boolean {
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun selected(key: String) = (acceptance[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(receipts::get) }
        fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        val implementations = selected("implementation_receipts").filter { receipt ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                (receipt["arguments"] as? JsonObject)?.text("action") in setOf("commit", "commit_staged_patch") &&
                receipt.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}"))
        }
        if (implementations.isEmpty()) return false
        val attempts = latestAttempts(receipts)
        val latestHead = receipts.values.lastOrNull { it["trusted"] == JsonPrimitive(true) && it["success"] == JsonPrimitive(true) && it["head_observation"] == JsonPrimitive(true) && it.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}")) }?.get("source_head_sha")
        return implementations.all { implementation ->
            if (latestHead != null && latestHead != implementation["source_head_sha"]) return@all false
            val pages = selected("test_receipts").filter { test ->
                val data = root(test)
                test["trusted"] == JsonPrimitive(true) &&
                    test["success"] == JsonPrimitive(true) &&
                    test.text("tool").substringBefore("__") == "github_ci" &&
                    (test["arguments"] as? JsonObject)?.text("action") == "jobs" &&
                    data?.get("head_sha") == implementation["source_head_sha"] &&
                    (data?.get("run_attempt") as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true
            }.mapNotNull(::root).groupBy { it["run_id"] to it["run_attempt"] }
            pages.any { (identity, observations) ->
                val run = (identity.first as? JsonPrimitive)?.contentOrNull ?: return@any false
                val attempt = (identity.second as? JsonPrimitive)?.intOrNull ?: return@any false
                if (attempt != attempts[run]) return@any false
                val allJobs = GitHubWorkflowJobEvidence.completeJobs(observations)
                val tests = allJobs.orEmpty().filter { Regex("(?i)\\b(?:tests?|unit|instrumentation)\\b").containsMatchIn(it.text("name")) }
                allJobs != null && tests.isNotEmpty() && tests.all { it.text("status") == "completed" && it.text("conclusion") == "success" }
            }
        }
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
            val implementedAndTested = acceptance != null && engineeringAcceptance(acceptance, receipts)
            val passed = covered && !unanswered && (informational(task) || implementedAndTested)
            buildJsonObject {
                put("id", id(requirement))
                put("requirement", requirement)
                put(
                    "status",
                    if (passed) {
                        "PASSED"
                    } else if (receipts.isEmpty()) {
                        "PENDING"
                    } else if (informational(task)) {
                        "BLOCKED"
                    } else {
                        "UNTESTED"
                    }
                )
                put("acceptance", if (passed) if (informational(task)) "Receipt-supported answer; no implementation or test execution inferred" else "Published implementation and successful test jobs at the exact revision and attempt" else "Requires explicit acceptance evidence for this requirement")
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
