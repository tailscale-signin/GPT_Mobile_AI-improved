package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A status assessment cites captured sources without rewriting them or the accepted task. */
internal object GitHubStatusEvidence {
    data class Coverage(val item: String, val available: Boolean, val receiptIds: Set<String>, val reason: String = "", val summary: String = "", val policyEvidence: JsonObject? = null) {
        fun row() = buildJsonObject {
            put("item", item)
            put("observed", available)
            put("receipt_ids", JsonArray(receiptIds.sorted().map(::JsonPrimitive)))
            if (reason.isNotBlank()) put("reason", reason)
            if (summary.isNotBlank()) put("summary", summary)
            policyEvidence?.forEach { (key, value) -> put(key, value) }
        }
    }

    data class CheckInventory(
        val receiptIds: Set<String> = emptySet(),
        val observedChecks: Int = 0,
        val totalChecks: Int? = null,
        val observedStatuses: Int = 0,
        val totalStatuses: Int? = null,
        val complete: Boolean = false,
        val outcomes: Map<String, Int> = emptyMap(),
        val nextPage: Int? = null
    ) {
        fun row() = buildJsonObject {
            put("observed_checks", observedChecks)
            totalChecks?.let { put("reported_total_checks", it) }
            put("observed_statuses", observedStatuses)
            totalStatuses?.let { put("reported_total_statuses", it) }
            put("complete", complete)
            put("receipt_ids", JsonArray(receiptIds.sorted().map(::JsonPrimitive)))
            put("counting_method", "Unique captured identities on contiguous pages for the selected immutable commit; upstream totals are reported separately.")
        }
    }

    fun data(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let(GitHubSemanticSummary::data)

    /** Read-source observations remain valid evidence for their source, never for the accepted target. */
    fun selected(receipts: Map<String, JsonObject>, acceptedRepository: String?): Map<String, JsonObject> = receipts.filterValues { receipt ->
        val role = receipt.text("repository_role")
        if (acceptedRepository == null) {
            role in setOf("", "WRITE_DESTINATION")
        } else {
            role == "WRITE_DESTINATION" && receipt.text("repository").equals(acceptedRepository, true)
        }
    }

    private fun pageSize(receipt: JsonObject, default: Int): Int? {
        val sizes = listOfNotNull(data(receipt)?.integer("per_page"), (receipt["arguments"] as? JsonObject)?.integer("per_page"))
        return if (sizes.any { it !in 1..100 } || sizes.distinct().size > 1) null else sizes.firstOrNull() ?: default
    }

    fun fresh(receipt: JsonObject, now: Long, epoch: Long?): Boolean {
        val observed = (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull ?: return false
        return receipt["trusted"] == JsonPrimitive(true) &&
            receipt.text("repository_role") in setOf("", "WRITE_DESTINATION") &&
            receipt["verification_eligible"] != JsonPrimitive(false) &&
            (epoch == null || receipt["epoch"] == JsonPrimitive(epoch)) &&
            observed > 0 &&
            now - observed in 0..GitHubEvidencePolicy.MAX_AGE_MS
    }

    fun operation(receipt: JsonObject, tool: String, action: String): Boolean =
        receipt.text("tool").substringBefore("__") == tool && (receipt["arguments"] as? JsonObject)?.text("action") == action

    fun checks(receipts: Map<String, JsonObject>, head: String, now: Long, epoch: Long?): CheckInventory {
        if (!head.matches(Regex("[a-fA-F0-9]{40}"))) return CheckInventory()
        val pages = linkedMapOf<Int, Pair<String, JsonObject>>()
        val pageSizes = linkedMapOf<Int, Int?>()
        receipts.forEach { (id, receipt) ->
            if (!operation(receipt, "github_context", "checks") || receipt["success"] != JsonPrimitive(true) || !fresh(receipt, now, epoch)) return@forEach
            val data = data(receipt) ?: return@forEach
            val args = receipt["arguments"] as? JsonObject
            if (data.text("commit_sha") != head || args?.text("commit_sha").orEmpty().let { it.isNotBlank() && it != head }) return@forEach
            if (data["observationTruncated"] == JsonPrimitive(true)) return@forEach
            val number = data.integer("page") ?: args?.integer("page") ?: 1
            if (number !in 1..1000 || args?.integer("page")?.let { it != number } == true) return@forEach
            // Refreshing page one starts a new mutable inventory at the same SHA.
            // Older trailing pages cannot silently complete that new traversal.
            if (number == 1) {
                pages.clear()
                pageSizes.clear()
            }
            pages[number] = id to data
            pageSizes[number] = pageSize(receipt, 100)
        }
        if (pages.isEmpty()) return CheckInventory()
        val runs = linkedMapOf<String, JsonObject>()
        val statuses = linkedMapOf<String, JsonObject>()
        val checkTotals = mutableSetOf<Int>()
        val statusTotals = mutableSetOf<Int>()
        var intact = pageSizes.values.all { it != null } && pageSizes.values.distinct().size == 1
        val ids = linkedSetOf<String>()
        pages.toSortedMap().forEach { (page, source) ->
            val (id, data) = source
            ids += id
            val checkDetails = data["check_details"] as? JsonObject
            val statusDetails = data["status_details"] as? JsonObject
            val checkTotal = data.integer("checks") ?: checkDetails?.integer("total_count")
            val statusTotal = data.integer("statuses") ?: statusDetails?.integer("total_count")
            checkTotal?.takeIf { it >= 0 }?.let(checkTotals::add)
            statusTotal?.takeIf { it >= 0 }?.let(statusTotals::add)
            if (checkTotal == null || statusTotal == null) intact = false
            if (checkDetails?.integer("total_count")?.let { it != checkTotal } == true || statusDetails?.integer("total_count")?.let { it != statusTotal } == true) intact = false
            fun collect(rows: JsonArray?, total: Int?, target: MutableMap<String, JsonObject>, check: Boolean) {
                if (rows == null) {
                    if (total != 0) intact = false
                    return
                }
                rows.forEachIndexed { index, raw ->
                    val row = raw as? JsonObject
                    if (row == null) {
                        intact = false
                        return@forEachIndexed
                    }
                    val identity = row.text("id").takeIf(String::isNotBlank)
                    if (identity != null && identity.toLongOrNull()?.let { it > 0 } != true) intact = false
                    if (identity == null && pages.size > 1) intact = false
                    val key = identity ?: "$page:$index"
                    if (target.putIfAbsent(key, row) != null) intact = false
                    if (check && (row.text("status").isBlank() || "conclusion" !in row || row.text("head_sha").let { it.isNotBlank() && it != head })) intact = false
                    if (!check && row.text("state").isBlank()) intact = false
                }
            }
            collect(checkDetails?.get("check_runs") as? JsonArray, checkTotal, runs, true)
            collect(statusDetails?.get("statuses") as? JsonArray, statusTotal, statuses, false)
        }
        val lastPage = pages.keys.max()
        val contiguous = pages.keys == (1..lastPage).toSet()
        val last = pages.getValue(lastPage).second
        val totalChecks = checkTotals.singleOrNull()
        val totalStatuses = statusTotals.singleOrNull()
        val terminal = last["has_more"] != JsonPrimitive(true)
        val complete = intact && contiguous && terminal && totalChecks == runs.size && totalStatuses == statuses.size
        val outcomes = if (runs.values.all { it.text("status").isNotBlank() && "conclusion" in it }) {
            runs.values.groupingBy { if (it.text("status") != "completed") "running" else it.text("conclusion").ifBlank { "unknown" } }.eachCount()
        } else {
            emptyMap()
        }
        return CheckInventory(
            ids,
            runs.size,
            totalChecks,
            statuses.size,
            totalStatuses,
            complete,
            outcomes,
            (lastPage + 1).takeIf { contiguous && intact && last["has_more"] == JsonPrimitive(true) && lastPage < 1000 }
        )
    }

    fun openPullPages(receipts: Map<String, JsonObject>, now: Long, epoch: Long?): Map<String, JsonObject> {
        val pages = linkedMapOf<Int, Pair<String, JsonObject>>()
        receipts.forEach { (id, receipt) ->
            if (!operation(receipt, "github_pr", "list") || receipt["success"] != JsonPrimitive(true) || !fresh(receipt, now, epoch)) return@forEach
            val args = receipt["arguments"] as? JsonObject ?: return@forEach
            if (args.text("state") !in setOf("", "open")) return@forEach
            val data = data(receipt) ?: return@forEach
            val rows = (data["items"] ?: data["pull_requests"]) as? JsonArray ?: return@forEach
            if (data.integer("returned")?.let { it != rows.size } == true || rows.any { it !is JsonObject }) return@forEach
            val page = data.integer("page") ?: args.integer("page") ?: 1
            if (page !in 1..1000 || args.integer("page")?.let { it != page } == true) return@forEach
            if (page == 1) pages.clear()
            pages[page] = id to receipt
        }
        return pages.toSortedMap().values.toMap()
    }

    fun coverage(receipts: Map<String, JsonObject>, now: Long, epoch: Long, task: String, checks: CheckInventory): List<Coverage> {
        val current = receipts.filterValues { it["success"] == JsonPrimitive(true) && fresh(it, now, epoch) }
        fun matching(tool: String, action: String, predicate: (JsonObject, JsonObject) -> Boolean = { _, _ -> true }) = current.filterValues { receipt ->
            operation(receipt, tool, action) && data(receipt)?.let { predicate(it, (receipt["arguments"] as? JsonObject) ?: JsonObject(emptyMap())) } == true
        }
        val context = matching("github_context", "status").entries.lastOrNull()
        val contextData = context?.value?.let(::data)
        val head = contextData?.text("commit_sha").orEmpty()
        val branch = contextData?.text("branch").orEmpty()
        val contextIds = setOfNotNull(context?.key)
        val selected = head.matches(Regex("[a-fA-F0-9]{40}")) && branch.isNotBlank() && (contextData?.get("repository") as? JsonObject)?.text("full_name").orEmpty().isNotBlank()
        fun pageIntact(data: JsonObject, rows: JsonArray?): Boolean = rows != null && (data.integer("returned")?.let { it == rows.size } != false) && rows.all { it is JsonObject }
        val commits = matching("github_context", "commits") { data, _ ->
            data.text("commit_sha") == head &&
                pageIntact(data, data["items"] as? JsonArray) &&
                (data["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().all { it.text("sha").matches(Regex("[a-fA-F0-9]{40}")) }
        }
        val pulls = openPullPages(current, now, epoch)
        val pullRows = pulls.values.flatMap { receipt -> ((data(receipt)?.get("items") ?: data(receipt)?.get("pull_requests")) as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
        val pullScope = if (pulls.values.lastOrNull()?.let { data(it)?.get("has_more") } == JsonPrimitive(false)) "end of the captured list" else "bounded page; more PRs may be available"
        val latest = contextData?.get("latest_workflow") as? JsonObject
        val latestAvailable = listOf("head_sha", "head_branch", "status").all { latest?.text(it).orEmpty().isNotBlank() } ||
            (contextData?.get("status_availability") as? JsonObject)?.text("latest_workflow") == "No workflow run was returned for the selected ref"
        val rows = mutableListOf(
            Coverage("Selected branch and current commit", selected, contextIds),
            Coverage("Recent commits", selected && commits.isNotEmpty(), commits.keys, summary = commits.values.lastOrNull()?.let { "Recent commits: ${(data(it)?.get("items") as? JsonArray)?.size ?: 0} captured at the selected commit." }.orEmpty()),
            Coverage("Open pull requests (draft/ready)", pulls.isNotEmpty(), pulls.keys, summary = if (pulls.isEmpty()) "" else "Open pull requests: ${pullRows.size} captured rows ($pullScope)."),
            Coverage("Checks and statuses for the current commit", selected && checks.complete, checks.receiptIds, if (checks.complete) "" else "Captured ${checks.observedChecks} of ${checks.totalChecks ?: "unknown"} check runs and ${checks.observedStatuses} of ${checks.totalStatuses ?: "unknown"} statuses; complete matching pages are required."),
            Coverage("Latest workflow", latestAvailable, contextIds)
        )
        if (!GitHubStatusRequest.health(task)) return rows
        val policy = matching("github_context", "branch") { data, args ->
            val details = data["branch"] as? JsonObject
            (args.text("branch") == branch || details?.text("name") == branch) && (details?.get("commit") as? JsonObject)?.text("sha") == head
        }
        val latestPolicy = policy.values.lastOrNull()?.let(::data)
        val policyKnown = (latestPolicy?.get("policy") as? JsonObject)?.let {
            it["required_checks_complete"] == JsonPrimitive(true) && it.text("required_checks_state") in setOf("VERIFIED_NONE", "VERIFIED_PRESENT")
        } == true
        fun sourceState(key: String, expectsArray: Boolean): String {
            val observation = latestPolicy?.get(key) as? JsonObject ?: return "UNOBSERVED"
            val status = observation.integer("http_status")
            if (observation["available"] == JsonPrimitive(true)) {
                val data = observation["data"]
                val valid = if (expectsArray) {
                    (data as? JsonArray)?.all { it is JsonObject && it.text("type").isNotBlank() } == true
                } else {
                    data is JsonObject
                }
                return if (valid) "OBSERVED" else "INVALID_RESPONSE"
            }
            return when (status) {
                401, 403 -> "ACCESS_DENIED"
                404 -> "NOT_FOUND_OR_INACCESSIBLE"
                429 -> "RATE_LIMITED"
                null -> "UNAVAILABLE"
                else -> "HTTP_FAILURE"
            }
        }
        val protectionState = sourceState("protection", expectsArray = false)
        val rulesState = sourceState("rules", expectsArray = true)
        fun sourceStatus(key: String) = (latestPolicy?.get(key) as? JsonObject)?.integer("http_status")?.takeIf { it in 100..599 }
        fun sourceDescription(key: String, state: String) = state.lowercase().replace('_', ' ') + (sourceStatus(key)?.let { " (HTTP $it)" } ?: "")
        val observedChecks = ((latestPolicy?.get("policy") as? JsonObject)?.get("observed_required_checks") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull?.takeIf(String::isNotBlank) }.distinct()
        val policySummary = if (!policyKnown && (protectionState == "OBSERVED" || rulesState == "OBSERVED")) {
            "Partial required-check policy: ${observedChecks.size} required check name(s) captured from the available policy source. The unavailable source can add requirements; this does not establish the complete policy or merge readiness."
        } else {
            ""
        }
        val recovery = if (!policyKnown) {
            buildString {
                append("Required-check policy is unknown. Branch protection: ${sourceDescription("protection", protectionState)}. ")
                append("Applicable rules: ${sourceDescription("rules", rulesState)}. ")
                append(
                    if ("ACCESS_DENIED" in setOf(protectionState, rulesState) || "NOT_FOUND_OR_INACCESSIBLE" in setOf(protectionState, rulesState)) {
                        "Confirm the selected GitHub connection can read branch protection and applicable branch rules, then request Repository Health again at the current branch head. A 404 does not establish that protection is absent."
                    } else {
                        "Refresh both policy observations at the selected branch head."
                    }
                )
            }
        } else {
            ""
        }
        rows += Coverage(
            "Required-check policy",
            selected && policyKnown,
            policy.keys,
            recovery,
            summary = policySummary,
            policyEvidence = buildJsonObject {
                put("branch_protection_observation", protectionState)
                put("branch_rules_observation", rulesState)
                sourceStatus("protection")?.let { put("branch_protection_http_status", it) }
                sourceStatus("rules")?.let { put("branch_rules_http_status", it) }
            }
        )
        val failures = matching("github_ci", "runs") { data, args ->
            args.text("status") == "failure" &&
                args.text("branch") == branch &&
                (args.integer("page") ?: 1) == 1 &&
                pageIntact(data, data["workflow_runs"] as? JsonArray)
        }
        val failureSummary = failures.values.lastOrNull()?.let { receipt ->
            val data = data(receipt)
            val count = (data?.get("workflow_runs") as? JsonArray)?.size ?: 0
            "Recent failed workflows on $branch: $count captured in the requested recent page" + if (data?.get("has_more") == JsonPrimitive(true)) "; older failures remain outside this page." else "."
        }.orEmpty()
        rows += Coverage("Recent failures", selected && failures.isNotEmpty(), failures.keys, summary = failureSummary)
        val inventoryComplete = pulls.isNotEmpty() &&
            pulls.values.lastOrNull()?.let { data(it)?.get("has_more") } == JsonPrimitive(false) &&
            pulls.values.map { pageSize(it, 20) }.let { sizes -> sizes.all { it != null } && sizes.distinct().size == 1 } &&
            pulls.values.toList().dropLast(1).all { receipt -> ((data(receipt)?.get("items") ?: data(receipt)?.get("pull_requests")) as? JsonArray)?.size == pageSize(receipt, 20) } &&
            pullRows.mapNotNull { it.integer("number") }.let { it.size == pullRows.size && it.distinct().size == it.size } &&
            pulls.values.map { data(it)?.integer("page") ?: ((it["arguments"] as? JsonObject)?.integer("page") ?: 1) }.toSet().let { pages -> pages == (1..(pages.maxOrNull() ?: 1)).toSet() }
        val reviews = matching("github_pr", "context") { _, args -> args["include_health"] == JsonPrimitive(true) }
        val reviewsKnown = pullRows.all { pull ->
            val number = pull.integer("number")
            val pullHead = (pull["head"] as? JsonObject)?.text("sha").orEmpty()
            val pullBase = (pull["base"] as? JsonObject)?.text("sha").orEmpty()
            number != null &&
                pullHead.isNotBlank() &&
                pullBase.isNotBlank() &&
                reviews.values.any { receipt ->
                    val args = receipt["arguments"] as? JsonObject
                    val health = data(receipt)?.get("merge_health") as? JsonObject
                    health != null && args?.integer("pull_number") == number && health.text("headSha") == pullHead && health.text("baseSha") == pullBase && health.text("review") in setOf("APPROVED", "NOT_REQUIRED", "REVIEW_REQUIRED", "CHANGES_REQUESTED")
                }
        }
        rows += Coverage("Outstanding review decisions", inventoryComplete && reviewsKnown, pulls.keys + reviews.keys, if (inventoryComplete && reviewsKnown) "" else "Every observed open PR needs a current head/base-matching review decision and the open-PR inventory must be complete.")
        return rows
    }

    fun requirements(task: String, accepted: List<String>?, coverage: List<Coverage>, receipts: Map<String, JsonObject>): List<JsonObject> {
        val current = GitHubStatusRequest.requirements(task)
        val criteria = accepted ?: current
        val requirements = GitHubStatusRequest.requirements("status")
        val byItem = coverage.associateBy(Coverage::item)
        return criteria.map { criterion ->
            val items = when (criterion) {
                requirements[0] -> listOf("Selected branch and current commit")
                requirements[1] -> listOf("Recent commits", "Open pull requests (draft/ready)")
                requirements[2] -> listOf("Checks and statuses for the current commit", "Latest workflow")
                // The deterministic status renderer explicitly lists every
                // unavailable observation and keeps policy/health unknown.
                // Reporting that uncertainty does not require those unknowns
                // to become healthy; their separate obligations remain open.
                requirements[3] -> listOf("Selected branch and current commit")
                GitHubStatusRequest.POLICY_REQUIREMENT -> listOf("Required-check policy")
                GitHubStatusRequest.FAILURES_REQUIREMENT -> listOf("Recent failures")
                GitHubStatusRequest.REVIEWS_REQUIREMENT -> listOf("Outstanding review decisions")
                else -> emptyList()
            }
            val observations = items.mapNotNull(byItem::get)
            val passed = items.isNotEmpty() && observations.size == items.size && observations.all(Coverage::available)
            val ids = observations.flatMapTo(linkedSetOf(), Coverage::receiptIds)
            buildJsonObject {
                put("id", GitHubRequestCompletion.id(criterion))
                put("requirement", criterion)
                put("status", if (passed) "PASSED" else "BLOCKED")
                put("acceptance_state", if (passed) "PASS" else "BLOCKED")
                put("acceptance_evaluator_branch", if (criterion == requirements[3]) "STATUS_UNCERTAINTY_REPORT" else "STATUS_OBSERVATION")
                if (!passed) put("acceptance_mismatch", if (items.isEmpty()) "UNSUPPORTED_CRITERION" else "MATCHING_OBSERVATION_INCOMPLETE")
                put("receipt_ids", JsonArray(ids.sorted().map(::JsonPrimitive)))
                put("missing_evidence_ids", JsonArray(emptyList()))
                put(
                    "source_receipts",
                    JsonArray(
                        ids.sorted().map { id ->
                            buildJsonObject {
                                put("receipt_id", id)
                                val receipt = receipts[id]
                                (receipt?.get("original_result_sha256") ?: receipt?.get("result_sha256"))?.let { put("source_result_sha256", it) }
                                receipt?.get("observed_at_ms")?.let { put("observed_at_ms", it) }
                            }
                        }
                    )
                )
                if (!passed) put("reason", observations.filterNot(Coverage::available).joinToString("; ") { it.reason.ifBlank { "${it.item}: no complete current matching receipt" } }.ifBlank { "The retained criterion has no supported status evaluator; it has not been replaced or marked complete." })
            }
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
