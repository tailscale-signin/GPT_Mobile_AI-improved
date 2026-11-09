package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Non-code requests have their own final-state proof; they cannot borrow implementation/test acceptance. */
internal object GitHubOperationAcceptance {
    data class Assessment(val state: GitHubAcceptanceLedger.State, val reason: String, val repository: String? = null)
    private data class Request(val resource: String, val action: String, val state: String? = null, val number: String? = null, val branch: String? = null, val base: String? = null, val title: String? = null, val repository: String? = null)
    private val singleOperation = Regex("(?i)^\\s*(?:please\\s+)?(?:(?:can|could|would|will) you (?:please )?)?(create|make|add|delete|remove|update|edit|close|reopen)\\s+(?:(?:a|an|the|new|existing|github)\\s+)*(branch|issue|pull request|pr)\\b")
    private const val QUOTED = "(?:`[^`\\r\\n]+`|\"[^\"\\r\\n]+\"|'[^'\\r\\n]+')"
    private const val NAME = "(?:$QUOTED|[^\\s`\"',;!?]+)"
    private const val REPOSITORY = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
    private val branchTail = Regex("^(?:(?:named|called)\\s+)?($NAME)?(?:\\s*\\bfrom\\s+(?:branch\\s+)?($NAME))?(?:\\s*\\bin\\s+(?:repository\\s+)?($REPOSITORY))?$", RegexOption.IGNORE_CASE)
    private val entityTail = Regex("^(?:#?([1-9][0-9]*))?(?:\\s*\\b(?:titled|named|called)\\s+($QUOTED))?(?:\\s*\\bin\\s+(?:repository\\s+)?($REPOSITORY))?$", RegexOption.IGNORE_CASE)

    private fun request(requirement: String): Request? {
        val text = GitHubTaskContinuity.request(requirement).trim().removePrefix("- ")
        val found = singleOperation.find(text) ?: return null
        val verb = found.groupValues[1].lowercase()
        val resource = found.groupValues[2].lowercase().let { if (it in setOf("pr", "pull request")) "pull_request" else it }
        val action = when (verb) {
            "create", "make", "add" -> "create"
            "delete", "remove" -> if (resource == "branch") "delete" else return null
            else -> "update"
        }
        if (resource == "branch" && action == "update") return null
        // Fully consume the accepted grammar. Arbitrary trailing prose may add
        // code, CI, cleanup or field requirements and must never be discarded.
        val tail = text.substring(found.range.last + 1).trim().removeSuffix(".").trim()
        val parsed = (if (resource == "branch") branchTail else entityTail).matchEntire(tail) ?: return null
        fun group(index: Int) = parsed.groupValues[index].takeIf(String::isNotBlank)
        fun unquote(value: String) = if (value.firstOrNull() in setOf('`', '"', '\'')) value.drop(1).dropLast(1) else value
        val number = if (resource == "branch") null else group(1)
        val branch = if (resource != "branch" || group(1) == null) null else runCatching { normalizeGitHubBranch(unquote(requireNotNull(group(1)))) }.getOrNull() ?: return null
        val base = if (resource != "branch" || group(2) == null) null else runCatching { normalizeGitHubBranch(unquote(requireNotNull(group(2)))) }.getOrNull() ?: return null
        val title = if (resource == "branch") null else group(2)?.let(::unquote)
        val repository = group(3)?.let { raw -> runCatching { normalizeGitHubRepository(null, raw) }.getOrNull()?.let { "${it.first}/${it.second}" } ?: return null }
        val requestedState = when (verb) {
            "close" -> "closed"
            "reopen" -> "open"
            else -> null
        }
        if (action == "update" && requestedState == null && title == null || action == "delete" && branch == null || action == "create" && number != null) return null
        return Request(
            resource,
            action,
            requestedState,
            number,
            branch,
            base,
            title,
            repository
        )
    }

    fun supports(requirement: String): Boolean = request(requirement) != null || GitHubRepositoryLifecycle.intent(requirement) != null

    fun evaluate(requirement: String, acceptance: JsonObject, receipts: Map<String, JsonObject>, now: Long = System.currentTimeMillis()): Assessment? {
        GitHubRepositoryAcceptance.evaluate(requirement, acceptance, receipts, now)?.let { return it }
        val requested = request(requirement) ?: return null
        val operations = ids(acceptance, "operation_receipts")
        if (operations.isEmpty() || operations.any { it !in receipts }) return missing("Link the exact authorized operation receipt and a fresh readback of its final state")
        val matches = operations.mapNotNull { id -> receipts[id]?.takeIf(::live)?.let { id to it } }.filter { (_, receipt) ->
            val native = native(receipt) ?: return@filter false
            if (requested.repository != null && !requested.repository.equals(receipt.text("repository"), ignoreCase = true)) return@filter false
            native.text("action") == when (requested.resource) {
                "branch" -> if (requested.action == "create") "create_branch" else "delete_branch"
                "issue" -> if (requested.action == "create") "create_issue" else "update_issue"
                else -> if (requested.action == "create") "create_pull_request" else "update_pull_request"
            }
        }
        if (matches.isEmpty()) return missing("No live successful operation matches the requested resource and action")
        val assessed = matches.map { (id, operation) ->
            if (requested.resource == "branch") branch(requested, operation, acceptance, receipts, now) else entity(requested, id, operation, acceptance, receipts, now)
        }
        return assessed.firstOrNull { it.state == GitHubAcceptanceLedger.State.PASS }
            ?: assessed.firstOrNull { it.state == GitHubAcceptanceLedger.State.FAIL }
            ?: assessed.first()
    }

    private fun branch(requested: Request, operation: JsonObject, acceptance: JsonObject, receipts: Map<String, JsonObject>, now: Long): Assessment {
        val args = native(operation) ?: return missing("Branch operation arguments are unavailable")
        val expected = args.text("expected_head_sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return missing("Branch acceptance needs the exact expected head")
        val branch = runCatching { normalizeGitHubBranch(args.text("branch")) }.getOrNull() ?: return missing("Branch identity is unavailable")
        if (requested.branch != null && requested.branch != branch) return blocked("The operation affected a different branch from the requested name")
        if (requested.base != null && requested.base != args.text("base").ifBlank { "main" }) return blocked("The operation used a different source branch from the requested base")
        val result = root(operation) ?: return missing("Branch result is unavailable")
        if (result.text("branch") != branch) return blocked("The observed branch does not match the requested branch")
        fun readHead(candidate: JsonObject): String? {
            val candidateArgs = native(candidate) ?: return null
            val value = root(candidate) ?: return null
            return when (candidateArgs.text("action")) {
                "branch_context" -> (value["branch"] as? JsonObject)?.takeIf { it.text("name") == branch || candidateArgs.text("branch") == branch }?.let { (it["commit"] as? JsonObject)?.text("sha") }
                "resolve_context" -> value.text("commit_sha").takeIf { value.text("ref") == branch }
                else -> null
            }?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        }
        val observations = receipts.entries.filter { it.value !== operation && sameScope(it.value, operation) && live(it.value) && observed(it.value) >= observed(operation) }
        val latest = observations.filter { readHead(it.value) != null }.maxWithOrNull(compareBy<Map.Entry<String, JsonObject>> { observed(it.value) }.thenBy { receipts.keys.indexOf(it.key) })
        if (args.text("action") == "delete_branch") {
            if (latest != null) return blocked("A later live observation shows the deleted branch exists again")
            if (!fresh(operation, now)) return blocked("The branch absence proof is stale; reconcile its current state before completion")
            return if (result["deleted"] == JsonPrimitive(true) && result["absence_verified"] == JsonPrimitive(true) && result.text("observed_head_sha") == expected) {
                passed("The guarded deletion and subsequent remote absence were verified; the API does not provide atomic expected-SHA deletion")
            } else {
                blocked("A deletion acknowledgement alone does not prove remote absence")
            }
        }
        val context = result["context"] as? JsonObject
        val ref = result["receipt"] as? JsonObject
        val identityVerified = result.text("head_sha") == expected &&
            result.text("base_sha") == expected &&
            (context?.text("commit_sha") == expected && context.text("ref") == branch || ref?.text("ref") == "refs/heads/$branch" && (ref["object"] as? JsonObject)?.text("sha") == expected)
        if (!identityVerified) return blocked("The operation receipt does not identify the requested branch and exact expected head")
        if (latest != null && readHead(latest.value) != expected) return blocked("A later live observation shows that the requested branch head changed")
        if (observations.any { (_, candidate) -> native(candidate)?.let { it.text("action") == "delete_branch" && it.text("branch") == branch } == true && root(candidate)?.get("deleted") == JsonPrimitive(true) }) {
            return blocked("A subsequent operation deleted the requested branch; reconcile its final state")
        }
        val inlineProof = result["remote_state_verified"] == JsonPrimitive(true) && fresh(operation, now)
        val linkedProof = latest != null && latest.key in ids(acceptance, "verification_receipts") && fresh(latest.value, now)
        if (!inlineProof && !linkedProof) return blocked("Link a fresh live observation of the exact requested ref/head; the original write must not be replayed to repair missing or stale readback")
        return passed("The requested branch exists at the exact expected head, verified by a live remote read")
    }

    private fun entity(requested: Request, operationId: String, operation: JsonObject, acceptance: JsonObject, receipts: Map<String, JsonObject>, now: Long): Assessment {
        val args = native(operation) ?: return missing("Operation arguments are unavailable")
        val result = root(operation) ?: return missing("Operation result is unavailable")
        val written = (result[requested.resource] as? JsonObject) ?: result
        val number = written.text("number").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return missing("The operation did not identify the affected resource")
        if (requested.number != null && requested.number != number) return blocked("The operation affected a different resource number from the request")
        if (requested.title != null && args.text("title") != requested.title) return blocked("The operation did not request the specified title")
        val selector = if (requested.resource == "issue") "issue_number" else "pull_number"
        if (requested.action == "update" && args.text(selector) != number) return blocked("The operation returned a different resource number")
        if (requested.state != null && args.text("state") != requested.state) return blocked("The operation did not request the required open/closed state")
        val candidates = receipts.entries.filter { (id, receipt) ->
            if (id == operationId || !sameScope(receipt, operation) || !live(receipt) || observed(receipt) < observed(operation)) return@filter false
            val native = native(receipt) ?: return@filter false
            native.text("action") in (if (requested.resource == "issue") setOf("issue_context", "issue_headers") else setOf("pr_context", "pr_headers", "get_pull_request")) &&
                native.text(selector) == number
        }
        val latest = candidates.maxWithOrNull(compareBy<Map.Entry<String, JsonObject>> { observed(it.value) }.thenBy { receipts.keys.indexOf(it.key) })
            ?: return missing("Read back this exact resource after the operation with verify_live=true; an accepted write is not final-state verification")
        if (!fresh(latest.value, now)) return blocked("The resource readback is stale; obtain a fresh live observation before claiming the requested state")
        if (latest.key !in ids(acceptance, "verification_receipts")) return blocked("The linked readback is not the latest observed state of this resource")
        val readback = root(latest.value) ?: return missing("The readback result is unavailable")
        val observed = (readback[requested.resource] as? JsonObject) ?: readback
        if (observed.text("number") != number) return blocked("The readback identifies a different resource")
        val fields = if (requested.resource == "issue") listOf("title", "body", "state") else listOf("title", "body", "state", "draft")
        for (field in fields.filter { it in args }) {
            val matches = if (field == "body" && observed["body"] == null) {
                readback.text("body_sha256") == GitHubEvidenceBook.digest(args.text("body"))
            } else {
                observed[field] == args[field]
            }
            if (!matches) return blocked("The latest remote $field does not verify the requested value")
        }
        if (requested.resource == "pull_request") {
            val head = observed["head"] as? JsonObject
            val base = observed["base"] as? JsonObject
            val expectedHead = args.text("expected_head_sha").ifBlank { (written["head"] as? JsonObject)?.text("sha").orEmpty() }
            if (!expectedHead.matches(Regex("[a-fA-F0-9]{40}")) || (head?.text("sha") ?: observed.text("head_sha")) != expectedHead) return blocked("PR readback is not bound to the expected head")
            for ((field, nested, projection) in listOf(Triple("head", head, "head_ref"), Triple("base", base, "base_ref"))) {
                if (field in args && (nested?.text("ref") ?: observed.text(projection)) != args.text(field).substringAfter(':')) return blocked("The latest PR $field branch differs from the requested branch")
            }
        }
        if (requested.action == "create" && observed.text("state") != "open") return blocked("The newly requested resource is not open in the latest observation")
        return passed("The exact requested resource and changed fields were verified by a subsequent live read")
    }

    private fun ids(value: JsonObject, key: String) = (value[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun native(receipt: JsonObject): JsonObject? {
        val args = receipt["arguments"] as? JsonObject ?: return null
        return if (receipt.text("tool").substringBefore("__") == "github") args else GitHubOperationTool.nativeArguments(receipt.text("tool"), args)
    }
    private fun live(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) &&
        receipt["verification_eligible"] != JsonPrimitive(false) &&
        receipt["success"] == JsonPrimitive(true) &&
        GitHubEvidenceOrigin.of(receipt) == "LIVE" &&
        observed(receipt) > 0 &&
        GitHubExecutionLedger.receiptState(receipt) == GitHubExecutionLedger.ReceiptState.COMPLETED
    private fun observed(receipt: JsonObject) = (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0
    private fun fresh(receipt: JsonObject, now: Long) = observed(receipt) in (now - GitHubEvidencePolicy.MAX_AGE_MS)..now
    private fun sameScope(receipt: JsonObject, operation: JsonObject) = receipt.text("binding").isNotBlank() && receipt["binding"] == operation["binding"] && receipt["repository"] == operation["repository"]
    private fun root(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun passed(reason: String) = Assessment(GitHubAcceptanceLedger.State.PASS, reason)
    private fun missing(reason: String) = Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, reason)
    private fun blocked(reason: String) = Assessment(GitHubAcceptanceLedger.State.BLOCKED, reason)
}
