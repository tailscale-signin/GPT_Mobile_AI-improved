package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A frozen intake and independently verified work units; the plan grants no mutation authority. */
internal object GitHubIssueRemediation {
    private const val SCHEMA = "github.issue-remediation-cohort.v1"
    private const val MAX_ISSUES = 256
    const val INVENTORY_REQUIREMENT = "Freeze the complete observed open-issue cohort before reporting this bulk repair complete"
    private val bulk = Regex("(?i)(?:the\\s+)?(?:all|every|each|open|remaining|repository)\\s+(?:[a-z-]+\\s+){0,2}issues?\\b")
    private val command = Regex("(?im)(?:^|[.!?;\\n,]|\\band(?:\\s+then)?|\\bthen|\\balso)\\s*(?:please\\s+)?(?:(?:can|could|would|will) you (?:please )?|i (?:want|need|would like) you to )?((?:fix|repair|resolve|close)(?:\\s+(?:(?:and|then)\\s+)*(?:fix|repair|resolve|close))*)\\s+")
    private val pronoun = Regex("(?i)^(?:them(?:\\s+all)?|all(?:\\s+of\\s+them)?)\\b")
    private val quoted = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`|\"[^\"\\r\\n]*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'|(?m)^\\s*>[^\\r\\n]*")
    private val repairVerb = Regex("(?i)\\b(?:fix|repair|resolve)\\b")
    private val conditionalRepair = Regex("(?i)^\\s*,?\\s*(?:only\\s+)?(?:once|after|when)\\s+(?:(?:(?:they|these(?:\\s+issues?)?|those(?:\\s+issues?)?|the\\s+issues?)\\s+(?:are|is|have\\s+been|has\\s+been)\\s+)?(?:fixed|repaired|resolved)|(?:(?:you|we)\\s+)?(?:fix(?:ing)?|repair(?:ing)?|resolv(?:e|ing))\\s+(?:them(?:\\s+all)?|all\\s+of\\s+them|(?:the\\s+)?(?:issues?|(?:all|every|each|open|remaining)\\s+(?:open\\s+)?issues?)))\\b(?=\\s*(?:$|[.!?,;]|and\\b|then\\b))")
    private const val REPOSITORY_QUALIFIER = "\\s+(?:in|on|for)\\s+(?:(?:repository\\s+)?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)|(?:the|this|our|my|current|selected)\\s+(?:github\\s+)?repo(?:sitory)?\\b)"
    private val repositoryQualifier = Regex("(?i)^$REPOSITORY_QUALIFIER")
    private val issueClause = Regex("(?i)^\\s*(?:please\\s+)?(?:(?:can|could|would|will) you (?:please )?|i (?:want|need|would like) you to )?((?:review|inspect|examine|list|fix|repair|resolve|close)(?:\\s+(?:(?:and|then)\\s+)*(?:review|inspect|examine|list|fix|repair|resolve|close))*)\\s+((?:the\\s+)?(?:(?:all|every|each)(?:\\s+(?:open|remaining))?|open|remaining|repository)\\s+issues?\\b|them(?:\\s+all)?\\b|all(?:\\s+of\\s+them)?\\b)(?:$REPOSITORY_QUALIFIER)?")
    private val issueSeparator = Regex("(?i)^(?:\\s*[,;.]?\\s*(?:and(?:\\s+then)?|then|also)\\s+|\\s*[,;.\\n]\\s*)")

    private enum class WorkKind { REPAIR_AND_CLOSE, CLOSURE_ONLY }

    data class Context(val cohort: JsonObject, val previous: List<JsonObject> = emptyList()) {
        val groups: List<JsonObject> get() = (cohort["groups"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

        // Older snapshots retain their original repair obligations.
        val closureOnly: Boolean get() = cohort["work_kind"] == JsonPrimitive(WorkKind.CLOSURE_ONLY.name)
    }

    fun requested(task: String): Boolean = workKind(task) != null

    private fun workKind(task: String): WorkKind? {
        val request = GitHubTaskContinuity.request(task).replace(quoted, " ")
        val directives = command.findAll(request).filter { found ->
            val objectText = request.substring(found.range.last + 1)
            bulk.find(objectText)?.range?.first == 0 ||
                pronoun.containsMatchIn(objectText) &&
                bulk.containsMatchIn(request.substring(0, found.range.first))
        }.toList()
        if (directives.isEmpty()) return null
        val repair = directives.any { directive ->
            if (repairVerb.containsMatchIn(directive.groupValues[1])) return@any true
            val objectText = request.substring(directive.range.last + 1)
            val target = bulk.find(objectText)?.takeIf { it.range.first == 0 } ?: pronoun.find(objectText) ?: return@any false
            val qualifiedTail = objectText.substring(target.range.last + 1)
            val tail = repositoryQualifier.find(qualifiedTail)?.let { qualifiedTail.substring(it.range.last + 1) } ?: qualifiedTail
            // The condition must immediately qualify these issues. A later fix
            // of an unrelated file, or quoted example text, is not repair intent.
            conditionalRepair.containsMatchIn(tail.take(512))
        }
        return if (repair) WorkKind.REPAIR_AND_CLOSE else WorkKind.CLOSURE_ONLY
    }

    /** Consume the whole obligation; extra criteria or conditions cannot inherit issue-only acceptance. */
    fun issueOnlyObligation(requirement: String, repository: String): Boolean {
        var remaining = GitHubTaskContinuity.request(requirement).trim().removePrefix("- ").trimEnd('.', '!', '?').trim()
        var issueTarget = false
        var action = false
        while (remaining.isNotBlank()) {
            val clause = issueClause.find(remaining) ?: return false
            val target = clause.groupValues[2]
            if (Regex("(?i)\\bissues?$").containsMatchIn(target)) {
                issueTarget = true
            } else if (!issueTarget) {
                return false
            }
            if (clause.groupValues[3].isNotBlank() && !repository.equals(clause.groupValues[3], true)) return false
            val clauseAction = Regex("(?i)\\b(?:fix|repair|resolve|close)\\b").containsMatchIn(clause.groupValues[1])
            action = action || clauseAction
            remaining = remaining.substring(clause.range.last + 1)
            if (clauseAction) conditionalRepair.find(remaining)?.let { remaining = remaining.substring(it.range.last + 1) }
            if (remaining.isBlank()) return issueTarget && action
            val separator = issueSeparator.find(remaining) ?: return false
            remaining = remaining.substring(separator.range.last + 1)
            if (remaining.isBlank()) return false
        }
        return false
    }

    /** A fresh, unfiltered, single-page empty inventory can answer an issue-only repair directly. */
    fun emptyIntakeReport(
        task: String,
        context: Context,
        contract: JsonObject,
        receipts: Map<String, JsonObject>,
        now: Long,
        epoch: Long
    ): Pair<String, JsonObject>? {
        val repository = contract.text("repository")
        if (context.groups.isNotEmpty() || !issueOnlyObligation(task, repository)) return null
        val id = (context.cohort["inventory_receipt_ids"] as? JsonArray)?.singleOrNull() as? JsonPrimitive ?: return null
        val receipt = receipts[id.content] ?: return null
        if (!GitHubStatusEvidence.fresh(receipt, now, epoch)) return null
        val args = native(receipt) ?: return null
        val result = receipt["result"] as? JsonObject ?: return null
        if (receipt["review_projection"] != null || !completeEmptyOpenPage(args, result)) return null
        val criteria = contract["acceptance_criteria"] as? JsonArray ?: return null
        val answer = buildJsonObject {
            put(
                "claims",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("kind", "fact")
                            put("receipt_id", id)
                            put("pointer", if (result["data"] is JsonObject) "/data/returned" else "/returned")
                            put("value", 0)
                        }
                    )
                )
            )
            put(
                "requirement_claims",
                buildJsonObject {
                    for (criterion in criteria) {
                        val requirement = (criterion as? JsonPrimitive)?.contentOrNull ?: return null
                        if (requirement != INVENTORY_REQUIREMENT && !issueOnlyObligation(requirement, repository)) return null
                        put(GitHubRequestCompletion.id(requirement), JsonArray(listOf(JsonPrimitive(0))))
                    }
                }
            )
        }
        val report = GitHubEvidencePolicy.render(answer, receipts, now, epoch, task, acceptedContract = contract, issueWork = context).second
        if (report["completion_verified"] != JsonPrimitive(true)) return null
        return "The complete issue list for `$repository` contained **0 open issues**. There was nothing to repair in that list.\n\nSource: saved receipt `${id.content}`." to report
    }

    private val inventoryArguments = setOf("action", "state", "owner", "repo", "per_page", "page", "after", "sort", "direction", "response_detail", "verify_live")

    fun completeEmptyOpenPage(args: JsonObject, result: JsonObject): Boolean {
        if (args.text("action") !in setOf("list", "list_issues") ||
            args.text("state") != "open" ||
            args.keys.any { it !in inventoryArguments } ||
            "after" in args ||
            args["page"]?.let { it != JsonPrimitive(1) } == true ||
            result["observationTruncated"] == JsonPrimitive(true)
        ) {
            return false
        }
        val data = (result["data"] as? JsonObject) ?: result
        val progress = data["pagination_progress"] as? JsonObject
        return data.text("resource_type") == "issue" &&
            (data["items"] as? JsonArray)?.isEmpty() == true &&
            data["returned"] == JsonPrimitive(0) &&
            data["page"] == JsonPrimitive(1) &&
            data["total_count"]?.let { it != JsonPrimitive(0) } != true &&
            data["has_more"] == JsonPrimitive(false) &&
            data.text("traversal_state") == "COMPLETE" &&
            data["complete"] != JsonPrimitive(false) &&
            data["upstream_has_more"] != JsonPrimitive(true) &&
            data["pagination_inconsistent"] != JsonPrimitive(true) &&
            data["truncated"] != JsonPrimitive(true) &&
            progress?.get("complete") != JsonPrimitive(false) &&
            progress?.get("upstream_changed_during_traversal") != JsonPrimitive(true)
    }

    fun valid(cohort: JsonObject, task: String, binding: String, repository: String): Boolean =
        cohort["schema"] == JsonPrimitive(SCHEMA) &&
            cohort["binding"] == JsonPrimitive(binding) &&
            cohort["repository"] == JsonPrimitive(repository) &&
            cohort["request_digest"] == JsonPrimitive(GitHubEvidenceBook.digest(GitHubTaskContinuity.request(task))) &&
            (cohort["work_kind"] == null || cohort["work_kind"] == workKind(task)?.let { JsonPrimitive(it.name) }) &&
            (cohort["groups"] as? JsonArray)?.let { groups ->
                groups.size <= MAX_ISSUES &&
                    groups.all { row ->
                        row is JsonObject && number(row["issue_number"]) != null && row["id"] == JsonPrimitive("issue-${number(row["issue_number"])}")
                    } &&
                    groups.map { (it as JsonObject)["id"] }.distinct().size == groups.size
            } == true

    /** Complete page chains only; a later refresh cannot extend or shrink the accepted cohort. */
    fun freeze(task: String, binding: String, repository: String, receipts: Map<String, JsonObject>): JsonObject? {
        if (!requested(task)) return null
        data class Traversal(val pages: MutableList<Pair<String, JsonObject>>, var next: Int = 1, var after: JsonPrimitive? = null)
        val traversals = mutableMapOf<String, Traversal>()
        for ((id, receipt) in receipts.entries.take(4096)) {
            if (!eligible(receipt, binding, repository) || GitHubEvidenceOrigin.of(receipt) != "LIVE") continue
            val args = native(receipt) ?: continue
            if (args.keys.any { it !in inventoryArguments } || args.text("action") != "list_issues" || args.text("state").ifBlank { "open" } !in setOf("open", "all")) continue
            val result = root(receipt) ?: continue
            if ((receipt["result"] as? JsonObject)?.get("observationTruncated") == JsonPrimitive(true) ||
                result["truncated"] == JsonPrimitive(true) ||
                result["pagination_inconsistent"] == JsonPrimitive(true) ||
                (result["pagination_progress"] as? JsonObject)?.get("upstream_changed_during_traversal") == JsonPrimitive(true)
            ) {
                continue
            }
            if (result["resource_type"] != JsonPrimitive("issue") || result["items"] !is JsonArray) continue
            if ((result["returned"] as? JsonPrimitive)?.intOrNull != (result["items"] as JsonArray).size) continue
            val page = number(result["page"]) ?: continue
            if (page != number(args["page"] ?: JsonPrimitive(1))) continue
            val key = GitHubInventoryQuery.arguments("github_issue", JsonObject(args + ("action" to JsonPrimitive("list")))).toString()
            if (page == 1) traversals[key] = Traversal(mutableListOf())
            val traversal = traversals[key] ?: continue
            if (page != traversal.next || args["after"] != traversal.after) continue
            traversal.pages += id to result
            if (result["has_more"] == JsonPrimitive(true)) {
                val continuation = result["continuation"] as? JsonObject ?: continue
                if (number(continuation["page"]) != page + 1) continue
                traversal.next = page + 1
                traversal.after = continuation["after"] as? JsonPrimitive
                continue
            }
            if (result["has_more"] != JsonPrimitive(false) ||
                result.text("traversal_state") != "COMPLETE" ||
                result["complete"] == JsonPrimitive(false) ||
                result["upstream_has_more"] == JsonPrimitive(true) ||
                (result["pagination_progress"] as? JsonObject)?.get("complete") == JsonPrimitive(false)
            ) {
                continue
            }
            val rawItems = traversal.pages.flatMap { (_, value) -> value["items"] as JsonArray }
            if (rawItems.any { it !is JsonObject }) continue
            val items = rawItems.map { it as JsonObject }
            if (items.any { "pull_request" in it || number(it["number"]) == null || it.text("state") !in setOf("open", "closed") }) continue
            if (items.map { it["number"] }.distinct().size != items.size) continue
            val totals = traversal.pages.mapNotNull { (_, value) -> (value["total_count"] as? JsonPrimitive)?.intOrNull }.distinct()
            if (totals.size > 1 || totals.singleOrNull()?.let { it != items.size } == true) continue
            val open = items.filter { it["state"] == JsonPrimitive("open") }
            if (open.size > MAX_ISSUES) return null
            return buildJsonObject {
                put("schema", SCHEMA)
                put("binding", binding)
                put("repository", repository)
                put("request_digest", GitHubEvidenceBook.digest(GitHubTaskContinuity.request(task)))
                put("work_kind", requireNotNull(workKind(task)).name)
                put("inventory_receipt_ids", JsonArray(traversal.pages.map { JsonPrimitive(it.first) }))
                put("cohort_scope", "First complete observed open-issue inventory for this accepted task; not an atomic GitHub snapshot. Later issues require a new intake.")
                put("grouping", "Independent issue groups; shared repair/test receipts may link related issues without assuming their root causes match.")
                put(
                    "groups",
                    JsonArray(
                        open.sortedBy { number(it["number"]) }.map { issue ->
                            buildJsonObject {
                                put("id", "issue-${number(issue["number"])}")
                                put("issue_number", issue.getValue("number"))
                                put("title", issue.text("title").take(160))
                                issue["updated_at"]?.let { put("intake_updated_at", it) }
                            }
                        }
                    )
                )
            }
        }
        return null
    }

    fun requirement(group: JsonObject, context: Context): String = if (context.closureOnly) {
        "Close issue #${number(group["issue_number"])} in ${context.cohort.text("repository")} and verify its final state."
    } else {
        "Resolve issue #${number(group["issue_number"])} in ${context.cohort.text("repository")}: diagnose the report, publish a fix or targeted diagnostic improvement, verify tests at that revision, and verify closure."
    }

    fun requirements(task: String, context: Context?): List<String> =
        if (requested(task)) listOf(INVENTORY_REQUIREMENT) + context?.groups?.map { requirement(it, context) }.orEmpty() else emptyList()

    fun obligationAssessment(
        requirement: String,
        task: String,
        context: Context?,
        acceptance: JsonObject?,
        citedIds: Set<String>,
        receipts: Map<String, JsonObject>,
        now: Long
    ): GitHubRequestCompletion.Assessment? {
        if (context == null || !issueOnlyObligation(requirement, context.cohort.text("repository"))) return null
        if (context.groups.isNotEmpty() && !context.closureOnly) return null
        val binding = context.cohort.text("binding")
        val repository = context.cohort.text("repository")
        val inventoryIds = (context.cohort["inventory_receipt_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        val inventoryReceipts = inventoryIds.mapNotNull { id -> receipts[id]?.let { id to it } }.toMap()
        val frozen = freeze(task, binding, repository, inventoryReceipts)
        val sameInventory = if (context.cohort["work_kind"] == null) frozen?.filterKeys { it != "work_kind" } == context.cohort else frozen == context.cohort
        if (!valid(context.cohort, task, binding, repository) || inventoryIds.isEmpty() || !sameInventory) {
            return GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "The complete frozen issue inventory must remain backed by its trusted live receipts")
        }
        if (context.groups.isEmpty()) {
            return if (citedIds.containsAll(inventoryIds)) {
                GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.PASS, "The complete verified live intake contains no open issues; this issue-only obligation requires no code or closure mutation")
            } else {
                GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "Link the checked empty-cohort finding to every receipt in the complete issue intake")
            }
        }
        val rows = context.groups.map { group ->
            val mapping = acceptance?.get(GitHubRequestCompletion.id(this.requirement(group, context))) as? JsonObject
            assess(group, context, mapping, emptySet(), false, receipts, now)
        }
        val incomplete = rows.firstOrNull { it["acceptance_state"] != JsonPrimitive(GitHubAcceptanceLedger.State.PASS.name) }
        return if (incomplete == null) {
            GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.PASS, "Every issue in the frozen cohort has an authorized guarded closure and a verified subsequent live readback")
        } else {
            GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.entries.first { it.name == incomplete.text("acceptance_state") }, incomplete.text("acceptance"))
        }
    }

    fun inventory(context: Context?): JsonObject = buildJsonObject {
        val pass = context != null
        put("id", GitHubRequestCompletion.id(INVENTORY_REQUIREMENT))
        put("requirement", INVENTORY_REQUIREMENT)
        put("status", if (pass) "PASSED" else "UNTESTED")
        put("acceptance_state", if (pass) "PASS" else "NOT_TESTED")
        put("acceptance", if (pass) "The first complete observed cohort is frozen; later arrivals cannot erase or expand it" else "Read every page of the authorized open-issue inventory. Partial, inconsistent or over-capacity inventories cannot establish the cohort")
        put("receipt_ids", context?.cohort?.get("inventory_receipt_ids") ?: JsonArray(emptyList()))
        put("missing_evidence_ids", JsonArray(emptyList()))
    }

    /** Work through a large frozen cohort without dropping unselected obligations from acceptance. */
    fun verificationWindow(task: String, context: Context?, receipts: Map<String, JsonObject>, now: Long, semantic: JsonObject? = null): List<String> {
        val all = GitHubRequestCompletion.requirements(task, context, semantic).distinct()
        if (context == null) return all
        val maximum = GitHubClaimBatches.REQUIREMENTS_PER_BATCH * GitHubClaimBatches.MAX_BATCHES
        val base = all.filter { group(it, context) == null }
        if (base.size >= maximum) return all
        val unfinished = context.groups.map { group -> assess(group, context, null, emptySet(), false, receipts, now) }
            .filter { it["acceptance_state"] != JsonPrimitive("PASS") }
            .sortedBy { it["issue_verification_attempted"] == JsonPrimitive(true) }
            .map { it.text("requirement") }
        return base + unfinished.take(maximum - base.size)
    }

    fun group(requirement: String, context: Context?): JsonObject? = context?.groups?.singleOrNull { requirement(it, context) == requirement }

    /** Native publication returns sha, while approval preparation deliberately has no published revision. */
    fun publishedHead(action: String, arguments: JsonObject?, result: JsonObject?): String? {
        if (result == null || result["committed"] == JsonPrimitive(false)) return null
        val published = when (action) {
            "commit_staged_patch", "commit_files" -> true
            "stage_patch" -> arguments?.get("commit_immediately") == JsonPrimitive(true) && result["committed"] == JsonPrimitive(true)
            "recovery_context" -> result["approval_state"] == JsonPrimitive("PUBLISHED") &&
                result["remote_state_verified"] == JsonPrimitive(true) &&
                arguments?.text("engineering_task_id")?.takeIf(String::isNotBlank)?.let { it == result.text("engineering_task_id") } == true
            else -> false
        }
        return result.text("sha").takeIf { published && it.matches(Regex("[a-fA-F0-9]{40}")) }
    }

    fun publishedRevision(receipt: JsonObject): String? {
        if (GitHubExecutionLedger.receiptState(receipt) != GitHubExecutionLedger.ReceiptState.COMPLETED) return null
        val args = native(receipt) ?: return null
        return publishedHead(args.text("action"), args, root(receipt))?.takeIf { receipt["source_head_sha"] == JsonPrimitive(it) }
    }

    /** Re-evaluate saved proof against current receipts; another group's blocker never resets this one. */
    fun assess(
        group: JsonObject,
        context: Context,
        acceptance: JsonObject?,
        citedIds: Set<String>,
        claimsCovered: Boolean,
        receipts: Map<String, JsonObject>,
        now: Long
    ): JsonObject {
        val requirement = requirement(group, context)
        val id = GitHubRequestCompletion.id(requirement)
        val cohortDigest = GitHubEvidenceBook.digest(GitHubExecutionJournal.canonical(context.cohort).toString())
        val prior = context.previous.singleOrNull { it["id"] == JsonPrimitive(id) && it["issue_cohort_digest"] == JsonPrimitive(cohortDigest) }
        // An omitted or empty extraction role is not evidence that an earlier
        // completed phase stopped being true. Its retained receipts are rechecked below.
        val mapping = JsonObject((prior?.get("issue_acceptance") as? JsonObject).orEmpty() + acceptance.orEmpty().filterValues { it !is JsonArray || it.isNotEmpty() })
        val binding = context.cohort.text("binding")
        val repository = context.cohort.text("repository")
        val issue = requireNotNull(number(group["issue_number"]))
        fun good(receipt: JsonObject) = eligible(receipt, binding, repository)
        val currentDiagnosis = citedIds.filter { !context.closureOnly && receipts[it]?.let(::good) == true }
        val savedDiagnosis = (prior?.get("diagnosis_proof") as? JsonObject).orEmpty().filter { (receiptId, digest) ->
            !context.closureOnly && receipts[receiptId]?.let { good(it) && digest == JsonPrimitive(diagnosisDigest(it)) } == true
        }.keys
        val diagnosisIds = savedDiagnosis + if (claimsCovered) currentDiagnosis else emptyList()
        val issueEvidence = diagnosisIds.any { receiptId ->
            val receipt = receipts.getValue(receiptId)
            val args = native(receipt)
            val data = root(receipt)
            args?.text("action") == "issue_context" &&
                number(args["issue_number"]) == issue &&
                number(((data?.get("issue") as? JsonObject) ?: data)?.get("number")) == issue
        }
        val sourceEvidence = diagnosisIds.any { receiptId ->
            val receipt = receipts.getValue(receiptId)
            receipt.text("tool").githubOperationFamily() in setOf("github_read", "github_search", "github_diff") &&
                receipt.text("source_head_sha").matches(Regex("[a-fA-F0-9]{40}"))
        }
        val diagnosis = when {
            issueEvidence && sourceEvidence -> GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.PASS, "The diagnosis or explicit missing-cause finding links this issue's report and pinned source observations")
            else -> GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "Link verified diagnosis findings to this issue's context receipt and pinned source; a title or unrelated read is insufficient")
        }
        val implementationIds = if (context.closureOnly) emptyList() else ids(mapping, "implementation_receipts")
        val published = implementationIds.filter { receiptId -> receipts[receiptId]?.let { good(it) && publishedRevision(it) != null } == true }
        val revisions = JsonArray(published.mapNotNull { receipts[it]?.let(::publishedRevision) }.distinct().map(::JsonPrimitive))
        val change = if (published.isNotEmpty() && published.size == implementationIds.size) {
            GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.PASS, "A committed fix or targeted diagnostic improvement is linked; this does not establish physical-device resolution")
        } else {
            GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "Link the published commit receipt for this issue's fix or targeted diagnostic improvement; preparation and HTTP acceptance are insufficient")
        }
        val tests = if (change.state == GitHubAcceptanceLedger.State.PASS) {
            GitHubRequestCompletion.engineeringAcceptance(mapping, receipts.filterValues(::good), repository, requireLatestHead = false)
        } else {
            GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, "Tests must be linked to this group's published revision")
        }
        val guardedOperations = ids(mapping, "operation_receipts").filter { receiptId ->
            receipts[receiptId]?.let { receipt ->
                good(receipt) &&
                    native(receipt)?.let { args ->
                        args.text("action") == "update_issue" &&
                            number(args["issue_number"]) == issue &&
                            args["state"] == JsonPrimitive("closed") &&
                            args.text("expected_updated_at").isNotBlank()
                    } == true
            } == true
        }
        val closureMapping = JsonObject(mapping + ("operation_receipts" to JsonArray(guardedOperations.map(::JsonPrimitive))))
        val closed = GitHubOperationAcceptance.evaluate("Close issue #$issue in $repository", closureMapping, receipts.filterValues(::good), now)
        val closure = GitHubRequestCompletion.Assessment(closed?.state ?: GitHubAcceptanceLedger.State.NOT_TESTED, closed?.reason ?: "Link the guarded close operation and subsequent live readback")
        val stages = if (context.closureOnly) linkedMapOf("CLOSURE" to closure) else linkedMapOf("DIAGNOSIS" to diagnosis, "CHANGE" to change, "TEST" to tests, "CLOSURE" to closure)
        val state = when {
            stages.values.all { it.state == GitHubAcceptanceLedger.State.PASS } -> GitHubAcceptanceLedger.State.PASS
            stages.values.any { it.state == GitHubAcceptanceLedger.State.FAIL } -> GitHubAcceptanceLedger.State.FAIL
            stages.values.any { it.state == GitHubAcceptanceLedger.State.BLOCKED } -> GitHubAcceptanceLedger.State.BLOCKED
            else -> GitHubAcceptanceLedger.State.NOT_TESTED
        }
        val evidenceRoles = if (context.closureOnly) listOf("operation_receipts", "verification_receipts") else listOf("implementation_receipts", "test_receipts", "operation_receipts", "verification_receipts")
        val evidence = diagnosisIds + evidenceRoles.flatMap { ids(mapping, it) }.filter { receipts[it]?.let(::good) == true }
        return buildJsonObject {
            put("id", id)
            put("requirement", requirement)
            put("issue_group", group.getValue("id"))
            put("issue_number", issue)
            if (!context.closureOnly) {
                put("issue_revisions", revisions)
                put("issue_test_revision_scope", "PUBLISHED_FIX_REVISION; overall task and release acceptance still require the current repository head")
            }
            put("issue_cohort_digest", cohortDigest)
            put("issue_acceptance", mapping)
            put("issue_verification_attempted", acceptance != null || claimsCovered || prior?.get("issue_verification_attempted") == JsonPrimitive(true))
            if (!context.closureOnly) put("diagnosis_proof", buildJsonObject { if (diagnosis.state == GitHubAcceptanceLedger.State.PASS) diagnosisIds.forEach { put(it, diagnosisDigest(receipts.getValue(it))) } })
            put(
                "issue_phases",
                JsonArray(
                    stages.map { (stage, value) ->
                        buildJsonObject {
                            put("stage", stage)
                            put("state", value.state.name)
                            put("reason", value.reason)
                        }
                    }
                )
            )
            put(
                "status",
                when (state) {
                    GitHubAcceptanceLedger.State.PASS -> "PASSED"
                    GitHubAcceptanceLedger.State.FAIL -> "FAILED"
                    GitHubAcceptanceLedger.State.BLOCKED -> "BLOCKED"
                    GitHubAcceptanceLedger.State.NOT_TESTED -> "UNTESTED"
                }
            )
            put("acceptance_state", state.name)
            put("acceptance", stages.values.firstOrNull { it.state != GitHubAcceptanceLedger.State.PASS }?.reason ?: if (context.closureOnly) "Guarded issue closure and its subsequent live readback are verified" else "Diagnosis, concrete code action, exact-revision tests and issue closure are independently verified")
            put("receipt_ids", JsonArray(evidence.sorted().map(::JsonPrimitive)))
            put("missing_evidence_ids", JsonArray(emptyList()))
            put("acceptance_evaluator_branch", if (context.closureOnly) "OPERATION_ACCEPTANCE" else "REVISION_TEST_ACCEPTANCE")
        }
    }

    private fun eligible(receipt: JsonObject, binding: String, repository: String): Boolean =
        receipt["trusted"] == JsonPrimitive(true) &&
            receipt["success"] == JsonPrimitive(true) &&
            receipt["verification_eligible"] != JsonPrimitive(false) &&
            receipt["binding"] == JsonPrimitive(binding) &&
            receipt.text("repository").equals(repository, true) &&
            receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
            GitHubExecutionLedger.receiptState(receipt) == GitHubExecutionLedger.ReceiptState.COMPLETED &&
            (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true

    /** Authorization projections are rechecked separately; ordering and migration do not change source proof. */
    private fun diagnosisDigest(receipt: JsonObject): String = GitHubEvidenceBook.digest(
        GitHubExecutionJournal.canonical(
            JsonObject(
                receipt.filterKeys {
                    it in setOf("binding", "receipt_id", "call_id", "tool", "arguments", "repository", "branch", "source_head_sha", "observed_at_ms", "result")
                }
            )
        ).toString()
    )

    private fun ids(value: JsonObject, key: String) = (value[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.distinct().take(32)
    private fun number(value: kotlinx.serialization.json.JsonElement?): Int? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
    private fun native(receipt: JsonObject): JsonObject? = (receipt["arguments"] as? JsonObject)?.let { args -> if (receipt.text("tool").githubOperationFamily() == "github") args else GitHubOperationTool.nativeArguments(receipt.text("tool"), args) }
    private fun root(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
