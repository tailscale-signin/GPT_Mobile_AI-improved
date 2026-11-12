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
    private const val ACTION = "(?:add|implement|fix|repair|build|create|make|merge|publish|push|commit|delete|remove|change|update|run|test|deploy|install|cancel|rerun|continue|resume|apply)"
    private const val POLITE = "(?:please\\s+)?(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?"
    private val mutation = Regex("(?im)^(?:\\s*[-*\\d.)]+\\s*)?(?:(?:and|then|also|next)\\s+)?$POLITE$ACTION\\b")
    private val inspection = Regex("(?i)^$POLITE(?:review|inspect|examine|analy[sz]e|find|read|check|look at|compare|investigate|explain|describe|summarize|list|show)\\b")
    private val knowledge = Regex("(?i)^$POLITE(?:explain|describe|show(?: me)?)\\s+(?:how|why|what|whether)\\b")
    private val compoundMutation = Regex("(?i)(?:\\band(?:\\s+then)?|\\bthen|\\balso|\\bafterwards|,)\\s+$POLITE$ACTION\\b")
    private val quotedMaterial = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`|\"(?:\\\\.|[^\"\\\\])*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'")
    private val repositorySubject = Regex("(?i)\\b(?:repo(?:sitory)?|repositories|codebase|branches|branch|pull requests?|ci|workflows?|tests?|build|files?|features?|bugs?|fixes|patch(?:es)?|implementation|commits?)\\b|\\b[A-Za-z0-9_.-]+\\.(?:kt|java|md|py|js|ts|tsx|yml|yaml|json|xml)\\b")
    private val sessionReviewIntent = Regex("(?i)\\b(?:review|audit|diagnose|investigate|inspect|analy[sz]e|examine|summarize|read)\\b")
    private val sessionReviewSource = Regex("(?i)\\b(?:sessions?|captures?|diagnostic exports?|(?:app|diagnostic|saved|retained|attached|this|these)\\s+(?:logs?|traces?))\\b")

    private fun requestText(task: String): String = GitHubTaskContinuity.request(task)
        .replace(quotedMaterial, " ")
        .lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n")
        .replaceFirst(Regex("(?i)^(?:please[, ]+|i (?:want|need|would like) you to |can you |could you )+"), "").trim()

    private fun requestsAction(request: String): Boolean = request.split(Regex("(?<=[.!?;])\\s+|\\r?\\n")).any { clause ->
        val text = clause.trim()
        mutation.containsMatchIn(text) || inspection.containsMatchIn(text) && !knowledge.containsMatchIn(text) && compoundMutation.containsMatchIn(text)
    }

    /** Only explicit work on repository subjects requires tool evidence before review. */
    fun requiresRepositoryEvidence(task: String): Boolean {
        if (GitHubRepositoryLifecycle.intent(task) != null) return true
        val request = requestText(task)
        return requestsAction(request) && repositorySubject.containsMatchIn(request)
    }

    fun informational(task: String): Boolean {
        val request = requestText(task)
        return !requestsAction(request) &&
            (
                GitHubStatusRequest.matches(request) ||
                    Regex("(?i)^(?:please\\s+)?(?:review|audit|inspect|investigate|diagnose|analy[sz]e|summari[sz]e)\\b").containsMatchIn(request) ||
                    !GitHubTaskCoverage.matches(request) &&
                    (question.containsMatchIn(request) || Regex("(?i)^(?:give|provide|offer|perform|conduct) (?:me |a |an |the )*(?:full |detailed |in-depth )*(?:analysis|review|explanation|solutions|recommendations|report)\\b").containsMatchIn(request))
                )
    }

    private fun sessionReview(request: String): Boolean = sessionReviewIntent.containsMatchIn(request) && sessionReviewSource.containsMatchIn(request)

    /** A saved-session review may ask for current mutable facts alongside historical findings. */
    fun requiresLiveSessionReviewRefresh(task: String): Boolean {
        val request = requestText(task)
        val asksForCurrent = Regex("(?i)\\b(?:current|latest|live|refresh|right now|today)\\b").containsMatchIn(request)
        val namesMutableGitHubFacts = Regex("(?i)\\b(?:pull requests?|prs?|workflows?|ci|checks?|conclusions?|status|state)\\b").containsMatchIn(request)
        return informational(task) && sessionReview(request) && asksForCurrent && namesMutableGitHubFacts
    }

    fun requirements(task: String): List<String> {
        if (GitHubStatusRequest.matches(task)) {
            return listOf(
                "Identify the selected repository, branch and observed head revision",
                "Report recent commits and open pull requests with their observed scope",
                "Report exact-head check results and distinguish them from the latest workflow observation",
                "Identify unavailable checks and unknown required-check policy without claiming unverified health"
            )
        }
        val requested = GitHubTaskContinuity.request(task).lineSequence().map(String::trim)
            .filter { it.isNotBlank() && it !in setOf("Acceptance criteria:", "Request details:") && !it.startsWith("Budget:") && !it.startsWith("[github-workflow-preset:") }
            .map { it.removePrefix("- ") }.toList().ifEmpty { listOf("Requested answer") }
        // Reviews have two distinct evidence obligations: findings must cite retained
        // observations, while recommendations must be explicitly marked as inference.
        return if (informational(task) && sessionReview(requestText(task)) && requested.none { it.startsWith("Evidence-backed findings", true) }) {
            requested + listOf(
                "Evidence-backed findings link each material observation to its trusted receipt and preserve its observation time",
                "Recommendations are clearly labeled and distinguish evidence from inference"
            )
        } else {
            requested
        }
    }
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

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun engineeringAcceptance(acceptance: JsonObject, receipts: Map<String, JsonObject>, expectedRepository: String? = null): Assessment {
        fun selected(key: String) = (acceptance[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(receipts::get) }
        fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        fun eligible(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) &&
            receipt["verification_eligible"] != JsonPrimitive(false) &&
            receipt["success"] == JsonPrimitive(true) &&
            receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
            (expectedRepository == null || receipt.text("repository").equals(expectedRepository, true))
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

    fun evaluate(task: String, claims: List<JsonObject>, answer: JsonObject, receipts: Map<String, JsonObject>, now: Long = System.currentTimeMillis()): JsonObject {
        val informational = informational(task)
        val sessionReview = informational && sessionReview(requestText(task))
        val acceptedClaimStatuses = if (sessionReview) setOf("VERIFIED", "INFERRED", "HISTORICAL") else setOf("VERIFIED", "INFERRED")
        // An explicit unmeasured scope is a limitation, not an unsupported factual
        // claim. It still prevents that requirement from being counted as covered.
        val factualClaims = claims.filterNot(GitHubEvidencePolicy::explicitlyUnmeasured)
        val verified = factualClaims.isNotEmpty() && factualClaims.all { (it["status"] as? JsonPrimitive)?.contentOrNull in acceptedClaimStatuses }
        val requirements = requirements(task)
        val coverage = (answer["requirement_claims"] as? JsonObject).orEmpty()
        val pendingApprovals = GitHubPendingApproval.snapshot(receipts).isNotEmpty()
        val pendingDecision = receipts.values.any { receipt ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                receipt.text("tool").substringBefore("__") == "github_decide" &&
                receipt.text("error_code") in setOf("GITHUB_DECISION_REQUIRED", "USER_INPUT_REQUIRED")
        }
        val unanswered = pendingApprovals || pendingDecision
        val comparisonMissing = GitHubReviewComparison.incompleteReason(task, receipts)
        val rows = requirements.map { requirement ->
            val selected = (coverage[id(requirement)] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val selectedClaims = when {
                selected.isNotEmpty() -> selected.mapNotNull(claims::getOrNull)
                requirement.startsWith("Evidence-backed findings", true) -> claims.filter { it["kind"] in setOf(JsonPrimitive("fact"), JsonPrimitive("excerpt"), JsonPrimitive("inferred"), JsonPrimitive("calculation")) }
                requirement.startsWith("Recommendations are clearly labeled", true) -> claims.filter { it["kind"] == JsonPrimitive("recommendation") }
                sessionReview && requirement == requirements.first() -> claims
                requirements.size == 1 -> claims
                else -> emptyList()
            }
            val acceptance = (answer["acceptance"] as? JsonObject)?.get(id(requirement)) as? JsonObject
            fun claimReceiptIds(claim: JsonObject): Set<String> = buildSet {
                (claim["receipt_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(::add)
                (claim["sources"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { source ->
                    (source["receipt_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            val citedReceiptIds = selectedClaims.flatMapTo(linkedSetOf(), ::claimReceiptIds)
            val acceptanceReceiptIds = acceptance?.let { mapping ->
                listOf("implementation_receipts", "test_receipts", "operation_receipts", "verification_receipts")
                    .flatMap { key -> (mapping[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
                    .toSet()
            }.orEmpty()
            val allLinkedReceiptIds = citedReceiptIds + acceptanceReceiptIds
            val linkedReceiptIds = allLinkedReceiptIds.filterTo(linkedSetOf()) { receiptId -> receipts[receiptId]?.let { it["trusted"] == JsonPrimitive(true) && it["success"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) } == true }
            val missingReceiptIds = allLinkedReceiptIds - linkedReceiptIds
            val claimsCovered = selectedClaims.isNotEmpty() && selectedClaims.all { (it["status"] as? JsonPrimitive)?.contentOrNull in acceptedClaimStatuses }
            val recommendationRequirement = requirement.startsWith("Recommendations are clearly labeled", true)
            val recommendationClaimed = selectedClaims.any { it["kind"] == JsonPrimitive("recommendation") && it["status"] == JsonPrimitive("INFERRED") }
            val covered = claimsCovered && (!sessionReview || linkedReceiptIds.isNotEmpty()) && (!recommendationRequirement || recommendationClaimed)
            val assessment = acceptance?.let {
                GitHubOperationAcceptance.evaluate(requirement, it, receipts, now)?.let { operation ->
                    if (operation.state == GitHubAcceptanceLedger.State.PASS && GitHubRepositoryLifecycle.intent(requirement) != null && GitHubRepositoryAcceptance.requiresAdditionalEvidence(requirement)) {
                        engineeringAcceptance(it, receipts, operation.repository)
                    } else {
                        Assessment(operation.state, operation.reason)
                    }
                }
                    ?: engineeringAcceptance(it, receipts)
            }
            val implementedAndTested = assessment?.state == GitHubAcceptanceLedger.State.PASS
            val reviewBlocker = comparisonMissing?.takeIf { GitHubReviewComparison.requiresComparison(requirement) }
            val passed = covered && !unanswered && reviewBlocker == null && (informational || implementedAndTested)
            val state = when {
                passed -> GitHubAcceptanceLedger.State.PASS
                unanswered -> GitHubAcceptanceLedger.State.BLOCKED
                informational -> if (receipts.isEmpty()) GitHubAcceptanceLedger.State.NOT_TESTED else GitHubAcceptanceLedger.State.BLOCKED
                assessment?.state == GitHubAcceptanceLedger.State.PASS -> GitHubAcceptanceLedger.State.BLOCKED
                else -> assessment?.state ?: GitHubAcceptanceLedger.State.NOT_TESTED
            }
            buildJsonObject {
                put("id", id(requirement))
                put("requirement", requirement)
                put("receipt_ids", JsonArray(linkedReceiptIds.sorted().map(::JsonPrimitive)))
                put("missing_evidence_ids", JsonArray(missingReceiptIds.sorted().map(::JsonPrimitive)))
                if (sessionReview && linkedReceiptIds.isEmpty()) put("missing_evidence", "No trusted successful source receipt is linked to this finding")
                if (recommendationRequirement && !recommendationClaimed) put("missing_evidence", "A clearly labeled recommendation with verified source receipts is required")
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
                    } else if (reviewBlocker != null) {
                        reviewBlocker
                    } else if (informational && passed) {
                        if (linkedReceiptIds.isEmpty()) "Verified answer coverage" else "Receipt-supported answer coverage"
                    } else if (!covered && implementedAndTested) {
                        "Matching test evidence is present, but answer coverage for this requirement remains unverified"
                    } else {
                        assessment?.reason ?: "Requires explicit acceptance evidence for this requirement"
                    }
                )
                acceptance?.get("implementation_receipts")?.let { put("implementation_receipts", it) }
                acceptance?.get("test_receipts")?.let { put("test_receipts", it) }
                acceptance?.get("operation_receipts")?.let { put("operation_receipts", it) }
                acceptance?.get("verification_receipts")?.let { put("verification_receipts", it) }
            }
        }
        val complete = rows.all { it["status"] == JsonPrimitive("PASSED") }
        return buildJsonObject {
            put("answer_verified", verified)
            put("completion_verified", complete)
            put("task_outcome", if (complete) "COMPLETED" else "PARTIAL")
            put("requirements", JsonArray(rows))
            put("remaining_requirements", JsonArray(rows.filter { it["status"] != JsonPrimitive("PASSED") }))
            put("completion_scope", if (informational) "Answer coverage" else "Operation-specific remote state or implementation and revision-bound test evidence, as required by the request")
        }
    }
}
