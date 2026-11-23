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
        if (GitHubRepositoryLifecycle.intent(task) != null || GitHubIssueRemediation.requested(task)) return true
        val request = requestText(task)
        return requestsAction(request) && repositorySubject.containsMatchIn(request)
    }

    fun informational(task: String): Boolean {
        if (GitHubIssueRemediation.requested(task)) return false
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

    fun requirements(task: String): List<String> = requirements(task, null)

    fun requirements(task: String, issueWork: GitHubIssueRemediation.Context?): List<String> {
        if (GitHubStatusRequest.matches(task)) {
            return GitHubStatusRequest.requirements(task)
        }
        val lines = GitHubTaskContinuity.request(task).lineSequence().map(String::trim).toList()
        val header = lines.indexOfFirst { sectionTitle(it) == "acceptance criteria" }
        val criteria = if (header < 0 || !GitHubTestChecklist.integration(task)) {
            emptyList()
        } else {
            val body = lines.drop(header + 1)
            val detailsStart = body.indexOfFirst { sectionTitle(it) == "request details" || it.startsWith("[github-workflow-preset:") }
            val criteriaLines = if (detailsStart < 0) body else body.take(detailsStart)
            val additions = if (detailsStart < 0) emptyList() else body.drop(detailsStart + 1)
            val bullets = criteriaLines.filter { it.startsWith("- ") }
            if (bullets.isEmpty()) {
                emptyList()
            } else {
                val details = additions.filter {
                    it.isNotBlank() && sectionTitle(it) == null && !it.startsWith("Budget:") && !it.startsWith("[github-workflow-preset:")
                }
                (bullets + details).map { it.removePrefix("- ").trim() }.filter(String::isNotBlank).distinct()
            }
        }
        val requestedLines = criteria.ifEmpty {
            lines
                .filter { it.isNotBlank() && sectionTitle(it) == null && !it.startsWith("Budget:") && !it.startsWith("[github-workflow-preset:") }
                .map { it.removePrefix("- ") }.toList().ifEmpty { listOf("Requested answer") }
        }
        val requested = if (informational(task) || GitHubTestChecklist.integration(task)) requestedLines else requestedLines.flatMap(GitHubAcceptanceContract::split).distinct()
        // Reviews have two distinct evidence obligations: findings must cite retained
        // observations, while recommendations must be explicitly marked as inference.
        return if (informational(task) && sessionReview(requestText(task)) && requested.none { it.startsWith("Evidence-backed findings", true) }) {
            requested + listOf(
                "Evidence-backed findings link each material observation to its trusted receipt and preserve its observation time",
                "Recommendations are clearly labeled and distinguish evidence from inference"
            )
        } else {
            requested + GitHubIssueRemediation.requirements(task, issueWork)
        }
    }
    private val sectionHeading = Regex("""(?i)^(?:#{1,6}\s+)?(?:\*\*|__)?(acceptance criteria|request details):?(?:\*\*|__)?:?$""")
    internal fun sectionTitle(line: String): String? = sectionHeading.matchEntire(line)?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)

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

    internal data class Assessment(val state: GitHubAcceptanceLedger.State, val reason: String)

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    internal fun engineeringAcceptance(acceptance: JsonObject, receipts: Map<String, JsonObject>, expectedRepository: String? = null, requireLatestHead: Boolean = true): Assessment {
        fun selected(key: String) = (acceptance[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(receipts::get) }
        fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        fun eligible(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) &&
            receipt["verification_eligible"] != JsonPrimitive(false) &&
            receipt["success"] == JsonPrimitive(true) &&
            receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
            (expectedRepository == null || receipt.text("repository").equals(expectedRepository, true))
        val implementations = selected("implementation_receipts").filter { receipt ->
            eligible(receipt) &&
                ((receipt["arguments"] as? JsonObject)?.text("action") in setOf("commit", "commit_staged_patch") || GitHubIssueRemediation.publishedRevision(receipt) != null) &&
                receipt.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}"))
        }
        if (implementations.isEmpty()) return Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "No authorized published implementation receipt is linked to this requirement")
        val attempts = latestAttempts(receipts)
        val latestHead = receipts.values.lastOrNull { eligible(it) && it["head_observation"] == JsonPrimitive(true) && it.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}")) }?.get("source_head_sha")
        // Completed issue work records the tested fix revision independently. The
        // overall task and release retain this current-head check by default.
        if (requireLatestHead && implementations.any { latestHead != null && latestHead != it["source_head_sha"] }) {
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

    fun evaluate(task: String, claims: List<JsonObject>, answer: JsonObject, receipts: Map<String, JsonObject>, now: Long = System.currentTimeMillis(), omittedOptionalClaimIndexes: Set<Int> = emptySet(), issueWork: GitHubIssueRemediation.Context? = null): JsonObject {
        val informational = informational(task)
        val sessionReview = informational && sessionReview(requestText(task))
        val acceptedClaimStatuses = if (sessionReview) setOf("VERIFIED", "INFERRED", "HISTORICAL") else setOf("VERIFIED", "INFERRED")
        // An explicit unmeasured scope is a limitation, not an unsupported factual
        // claim. It still prevents that requirement from being counted as covered.
        val factualClaims = claims.filterIndexed { index, claim -> index !in omittedOptionalClaimIndexes && !GitHubEvidencePolicy.explicitlyUnmeasured(claim) }
        val verified = factualClaims.isNotEmpty() && factualClaims.all { (it["status"] as? JsonPrimitive)?.contentOrNull in acceptedClaimStatuses }
        val requirements = requirements(task, issueWork)
        val criteria = GitHubAcceptanceContract.criteria(task, requirements).associateBy { it.id }
        val completedCriteria = mutableSetOf<String>()
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
            val criterion = criteria.getValue(id(requirement))
            if (requirement == GitHubIssueRemediation.INVENTORY_REQUIREMENT && GitHubIssueRemediation.requested(task)) {
                return@map GitHubIssueRemediation.inventory(issueWork)
            }
            val selected = (coverage[id(requirement)] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val selectedClaims = when {
                id(requirement) in coverage -> selected.mapNotNull(claims::getOrNull)
                requirement.startsWith("Evidence-backed findings", true) -> claims.filter { it["kind"] in setOf(JsonPrimitive("fact"), JsonPrimitive("excerpt"), JsonPrimitive("inferred"), JsonPrimitive("calculation")) }
                requirement.startsWith("Recommendations are clearly labeled", true) -> claims.filter { it["kind"] == JsonPrimitive("recommendation") }
                sessionReview && requirement == requirements.first() -> claims
                requirements.size == 1 -> claims
                else -> emptyList()
            }
            val acceptance = if (informational) null else (answer["acceptance"] as? JsonObject)?.get(id(requirement)) as? JsonObject
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
            GitHubIssueRemediation.group(requirement, issueWork)?.let { group ->
                return@map GitHubIssueRemediation.assess(
                    group,
                    requireNotNull(issueWork),
                    acceptance,
                    citedReceiptIds,
                    selectedClaims.isNotEmpty() && selectedClaims.all { it.text("status") in acceptedClaimStatuses },
                    receipts,
                    now
                )
            }
            val allLinkedReceiptIds = citedReceiptIds + acceptanceReceiptIds
            val linkedReceiptIds = allLinkedReceiptIds.filterTo(linkedSetOf()) { receiptId -> receipts[receiptId]?.let { it["trusted"] == JsonPrimitive(true) && it["success"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) } == true }
            val missingReceiptIds = allLinkedReceiptIds - linkedReceiptIds
            val claimsCovered = selectedClaims.isNotEmpty() && selectedClaims.all { (it["status"] as? JsonPrimitive)?.contentOrNull in acceptedClaimStatuses }
            val recommendationRequirement = requirement.startsWith("Recommendations are clearly labeled", true)
            val recommendationClaimed = selectedClaims.any { it["kind"] == JsonPrimitive("recommendation") && it["status"] == JsonPrimitive("INFERRED") }
            val covered = claimsCovered && (!sessionReview || linkedReceiptIds.isNotEmpty()) && (!recommendationRequirement || recommendationClaimed)
            var acceptanceBranch = "ACCEPTANCE_MAPPING_REQUIRED"
            val issueAssessment = GitHubIssueRemediation.obligationAssessment(requirement, task, issueWork, answer["acceptance"] as? JsonObject, citedReceiptIds, receipts, now)
            val criterionAssessment = if (informational) null else GitHubCriterionAcceptance.evaluate(criterion, acceptance, linkedReceiptIds, receipts, now)
            val assessment = criterionAssessment?.also { acceptanceBranch = "CRITERION_SPECIFIC_ACCEPTANCE" } ?: issueAssessment?.also { acceptanceBranch = "OPERATION_ACCEPTANCE" } ?: acceptance?.let {
                GitHubOperationAcceptance.evaluate(requirement, it, receipts, now)?.let { operation ->
                    if (operation.state == GitHubAcceptanceLedger.State.PASS && GitHubRepositoryLifecycle.intent(requirement) != null && GitHubRepositoryAcceptance.requiresAdditionalEvidence(requirement)) {
                        acceptanceBranch = "REVISION_TEST_ACCEPTANCE"
                        engineeringAcceptance(it, receipts, operation.repository)
                    } else {
                        acceptanceBranch = "OPERATION_ACCEPTANCE"
                        Assessment(operation.state, operation.reason)
                    }
                }
                    ?: run {
                        acceptanceBranch = "REVISION_TEST_ACCEPTANCE"
                        engineeringAcceptance(it, receipts)
                    }
            }
            val implementedAndTested = assessment?.state == GitHubAcceptanceLedger.State.PASS
            val reviewBlocker = comparisonMissing?.takeIf { GitHubReviewComparison.requiresComparison(requirement) }
            val pendingDependencies = if (informational) emptyList() else criterion.dependencies.filter { it !in completedCriteria }
            val passed = covered && !unanswered && reviewBlocker == null && pendingDependencies.isEmpty() && (informational || implementedAndTested)
            if (passed) completedCriteria += criterion.id
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
                put("criterion_contract", criterion.json())
                put("pending_dependencies", JsonArray(pendingDependencies.map(::JsonPrimitive)))
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
                put("acceptance_evaluator_branch", if (informational) "INFORMATIONAL_CLAIMS" else acceptanceBranch)
                put("eligible_receipt_count", linkedReceiptIds.size)
                put("excluded_receipt_count", missingReceiptIds.size)
                put("receipt_count_scope", "LINKED_CRITERION_EVIDENCE")
                put(
                    "receipt_exclusion_reasons",
                    JsonArray(
                        missingReceiptIds.map { receiptId ->
                            when {
                                receipts[receiptId] == null -> "MISSING_IDENTITY"
                                receipts[receiptId]?.get("trusted") != JsonPrimitive(true) || receipts[receiptId]?.get("verification_eligible") == JsonPrimitive(false) -> "UNVERIFIED"
                                else -> "NOT_SUCCESSFUL"
                            }
                        }.distinct().sorted().map(::JsonPrimitive)
                    )
                )
                if (!passed) {
                    put(
                        "acceptance_mismatch",
                        when {
                            unanswered -> "UNRESOLVED_USER_DECISION"
                            reviewBlocker != null -> "COMPARISON_REQUIRED"
                            pendingDependencies.isNotEmpty() -> "DEPENDENCIES_INCOMPLETE"
                            selectedClaims.isEmpty() -> "NO_MATCHING_CLAIMS"
                            missingReceiptIds.isNotEmpty() -> "LINKED_RECEIPT_INELIGIBLE"
                            informational && receipts.isEmpty() -> "ACCEPTANCE_EVIDENCE_MISSING"
                            !claimsCovered -> "UNSUPPORTED_CLAIMS"
                            !covered -> "ACCEPTANCE_EVIDENCE_MISSING"
                            !informational && acceptance == null -> "ACCEPTANCE_MAPPING_REQUIRED"
                            !informational && !implementedAndTested -> "NO_EXECUTION_PROOF"
                            else -> "ACCEPTANCE_EVIDENCE_MISSING"
                        }
                    )
                }
                put(
                    "acceptance",
                    if (unanswered) {
                        "A user decision remains unresolved"
                    } else if (reviewBlocker != null) {
                        reviewBlocker
                    } else if (pendingDependencies.isNotEmpty()) {
                        "Earlier required deliverables remain unverified: ${pendingDependencies.joinToString(", ")}"
                    } else if (informational && passed) {
                        if (linkedReceiptIds.isEmpty()) "Verified answer coverage" else "Receipt-supported answer coverage"
                    } else if (informational) {
                        when {
                            receipts.isEmpty() -> "No repository observations were collected for this answer. Use an authorized source read for the request, then verify its claims; the answer and task remain unverified."
                            selectedClaims.isEmpty() -> "Link this requirement to a checked claim from the retained observations"
                            !claimsCovered -> "Check the claims mapped to this requirement against their cited observations"
                            recommendationRequirement && !recommendationClaimed -> "Link a labeled recommendation to verified source observations"
                            else -> "Link this finding to a trusted successful source observation"
                        }
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
            put("acceptance_contract", GitHubAcceptanceContract.json(task, requirements))
            put("task_kind", if (informational) "READ_ONLY" else "GENERAL")
            put("answer_verified", verified)
            put("completion_verified", complete)
            put("task_outcome", if (complete) "COMPLETED" else "PARTIAL")
            issueWork?.let { put("issue_cohort", it.cohort) }
            put("requirements", JsonArray(rows))
            put("remaining_requirements", JsonArray(rows.filter { it["status"] != JsonPrimitive("PASSED") }))
            put("completion_scope", if (informational) "Answer coverage" else "Operation-specific remote state or implementation and revision-bound test evidence, as required by the request")
        }
    }
}
