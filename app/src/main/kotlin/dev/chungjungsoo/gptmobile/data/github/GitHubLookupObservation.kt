package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** An exact lookup may request only a visible read on the accepted repository and connection. */
@JvmOverloads
internal fun GitHubLookupRoute.Request.firstRead(
    trustedTools: List<AgentToolDefinition>,
    repository: String,
    branch: String?,
    verifyLive: Boolean = false
): ProviderEvent.ToolCall? {
    val target = repository.split('/')
    if (target.size != 2 || target.any { it.isBlank() || it != it.trim() }) return null
    val candidates = trustedTools.filter { it.modelVisible && it.name.githubOperationFamily() == route.operation }
    val first = candidates.firstOrNull() ?: return null
    if (candidates.any { !it.name.githubReadFacadeMatches(first.name) }) return null
    if (route == GitHubLookupRoute.COMMITS && branch.isNullOrBlank()) return null
    val arguments = buildJsonObject {
        put("owner", target[0])
        put("repo", target[1])
        put("action", route.action)
        if (verifyLive) put("verify_live", true)
        number?.let { put(if (route == GitHubLookupRoute.ISSUE) "issue_number" else "pull_number", it) }
        state?.let { put("state", it) }
        if (newestFirst) {
            put("sort", "created")
            put("direction", "desc")
        }
        pageSize?.let { put("per_page", it) }
        if (route == GitHubLookupRoute.COMMITS) put("ref", requireNotNull(branch))
        if (filesOnly) {
            put("include_files", true)
            put("include_checks", false)
            if (patches) put("include_patches", true)
        }
    }
    val tool = candidates.firstOrNull {
        ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty() &&
            (it.contractSchema == it.inputSchema || ToolArgumentValidator.errors(arguments, it.contractSchema).isEmpty())
    } ?: return null
    return ProviderEvent.ToolCall("lookup-" + route.step, tool.name, arguments)
}

/** A small, complete API observation can be delivered without model replay or claim extraction. */
internal object GitHubLookupObservation {
    fun render(
        task: String,
        receipts: Map<String, JsonObject>,
        repository: String,
        branch: String?,
        now: Long,
        epoch: Long
    ): Pair<String, JsonObject>? {
        val request = GitHubLookupRoute.request(task) ?: return null
        val route = request.route
        val prose = GitHubTaskContinuity.request(task).lowercase(java.util.Locale.ROOT)
        // Retrieval receipts establish records; synthesis and unsupported temporal
        // ordering still need their ordinary reasoning/verification path.
        if (Regex("\\bsummari[sz]e\\b").containsMatchIn(prose) ||
            route in setOf(GitHubLookupRoute.BRANCHES, GitHubLookupRoute.TAGS, GitHubLookupRoute.WORKFLOWS) &&
            Regex("\\b(?:latest|recent|last [0-9]+)\\b").containsMatchIn(prose)
        ) {
            return null
        }
        if (repository.split('/').let { it.size != 2 || it.any(String::isBlank) }) return null
        val candidates = GitHubStatusEvidence.selected(receipts, repository).filterValues { row ->
            row["success"] == JsonPrimitive(true) &&
                GitHubStatusEvidence.fresh(row, now, epoch) &&
                row.text("tool").githubOperationFamily() == route.operation &&
                matches(request, row["arguments"] as? JsonObject, branch)
        }
        val (id, receipt) = candidates.entries.lastOrNull() ?: return null
        // A truncated projection may retain selectors, but it is not the requested record.
        if (receipt["review_projection"] != null) return null
        val envelope = receipt["result"] as? JsonObject ?: return null
        if (envelope["observationTruncated"] == JsonPrimitive(true)) return null
        val data = GitHubSemanticSummary.data(envelope)
        if (data["truncated"] == JsonPrimitive(true) || data["pagination_inconsistent"] == JsonPrimitive(true)) return null
        val body = if (request.number != null) record(request, data) else collection(request, data)
        if (body.isNullOrBlank() || body.length > 32_768) return null
        val ids = JsonArray(listOf(JsonPrimitive(id)))
        val text = "$body\n\nSource: `$repository`, saved receipt `$id`."
        val requirements = JsonArray(
            GitHubRequestCompletion.requirements(task).map { requirement ->
                buildJsonObject {
                    put("id", GitHubRequestCompletion.id(requirement))
                    put("requirement", requirement)
                    put("status", "PASSED")
                    put("acceptance_state", "PASS")
                    put("required", true)
                    put("acceptance", "The complete requested record or bounded collection was delivered from an authorized, current receipt.")
                    put("acceptance_evaluator_branch", "STATUS_OBSERVATION")
                    put("evidence_receipt_ids", ids)
                }
            }
        )
        return text to buildJsonObject {
            put("schema", "github.lookup-observation.v1")
            put("task", task)
            put("task_outcome", "COMPLETED")
            put("completion_verified", true)
            put("answer_verified", true)
            put("claims_verified", true)
            put("partial", false)
            put("resume_required", false)
            put("completion_scope", "REQUESTED_LOOKUP")
            put("repository", repository)
            put("requirements", requirements)
            put("remaining_requirements", JsonArray(emptyList()))
            put("evidence_receipt_ids", ids)
            put("verified_at_ms", now)
        }
    }

    private fun matches(request: GitHubLookupRoute.Request, arguments: JsonObject?, branch: String?): Boolean {
        val args = arguments ?: return false
        if (args.text("action") != request.route.action || args.keys.any { it !in allowedArguments }) return false
        if (args.integer("page")?.let { it != 1 } == true || "view_cursor" in args) return false
        val selector = if (request.route == GitHubLookupRoute.ISSUE) "issue_number" else "pull_number"
        if (request.number != null && args.integer(selector) != request.number) return false
        if (request.state != null && args.text("state") != request.state) return false
        if (request.state == null &&
            args.text("state").isNotEmpty() &&
            args.text("state") != if (request.route == GitHubLookupRoute.ISSUES) "open" else "all"
        ) {
            return false
        }
        if (request.newestFirst && (args.text("sort") != "created" || args.text("direction") != "desc")) return false
        if (request.route == GitHubLookupRoute.COMMITS && (branch.isNullOrBlank() || args.text("ref") != branch)) return false
        return !request.filesOnly ||
            args["include_files"] == JsonPrimitive(true) &&
            (!request.patches || args["include_patches"] == JsonPrimitive(true))
    }

    private fun collection(request: GitHubLookupRoute.Request, data: JsonObject): String? {
        val field = when (request.route) {
            GitHubLookupRoute.ISSUES -> "issues"
            GitHubLookupRoute.PULL_REQUESTS -> "pull_requests"
            GitHubLookupRoute.BRANCHES -> "branches"
            GitHubLookupRoute.COMMITS -> "commits"
            GitHubLookupRoute.WORKFLOWS -> "workflows"
            GitHubLookupRoute.RUNS -> "workflow_runs"
            GitHubLookupRoute.RELEASES -> "releases"
            GitHubLookupRoute.TAGS -> "tags"
            else -> return null
        }
        val rows = (data[field] ?: if (field == "commits") data["recent_commits"] ?: data["items"] else data["items"]) as? JsonArray ?: return null
        if (rows.size > 100 || rows.any { it !is JsonObject } || data.integer("returned")?.let { it != rows.size } == true) return null
        val progress = data["pagination_progress"] as? JsonObject
        if (data.integer("page") != 1 || progress?.get("upstream_changed_during_traversal") == JsonPrimitive(true)) return null
        if (data.integer("total_count")?.let { it < rows.size } == true ||
            progress?.integer("upstream_rows_returned")?.let { it != rows.size } == true ||
            progress?.integer("remote_page")?.let { it != 1 } == true
        ) {
            return null
        }
        val complete = data["has_more"] == JsonPrimitive(false) &&
            data["upstream_has_more"] != JsonPrimitive(true) &&
            data["history_truncated"] != JsonPrimitive(true) &&
            data["traversal_complete"] != JsonPrimitive(false) &&
            data.integer("total_count").let { it == null || it == rows.size } &&
            progress?.get("contiguous_from_first_page") != JsonPrimitive(false) &&
            progress?.get("complete") != JsonPrimitive(false) &&
            data["complete"] != JsonPrimitive(false)
        val count = request.requestedCount
        if (!complete && (count == null || count > rows.size)) return null
        val observed = rows.filterIsInstance<JsonObject>()
        if (request.state in setOf("open", "closed") && observed.any { it.text("state") != request.state }) return null
        val identities = observed.map { row ->
            when (request.route) {
                GitHubLookupRoute.ISSUES, GitHubLookupRoute.PULL_REQUESTS -> row.integer("number")?.takeIf { it > 0 }?.toString()
                GitHubLookupRoute.BRANCHES, GitHubLookupRoute.TAGS -> row.text("name").takeIf(String::isNotBlank)
                GitHubLookupRoute.COMMITS -> row.text("sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
                else -> row.text("id").takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }
            }
        }
        if (identities.any { it == null } || identities.distinct().size != identities.size) return null
        val noun = field.replace('_', ' ')
        val state = request.state?.takeUnless { it == "all" }?.let { "$it " }.orEmpty()
        if (rows.isEmpty()) return "No ${state}$noun were returned by the complete requested query."
        val selected = observed.take(count ?: observed.size)
        val table = selected.map { row ->
            val identity = row.text("number").ifBlank { row.text("name") }.ifBlank { row.text("sha") }.ifBlank { row.text("id") }
            val label = row.text("title").ifBlank { row.text("name") }.ifBlank { (row["commit"] as? JsonObject)?.text("message").orEmpty() }
            val stateText = row.text("state").ifBlank { row.text("conclusion").ifBlank { row.text("status") } }
            "| ${cell(identity)} | ${cell(label)} | ${cell(stateText)} |"
        }
        return "## ${noun.replaceFirstChar(Char::uppercaseChar)}\n\n" +
            "| Identity | Title or name | State |\n| --- | --- | --- |\n" + table.joinToString("\n") +
            if (complete) "\n\nThe requested query is complete (${observed.size} returned)." else "\n\nThe requested $count records are shown; additional records exist."
    }

    private fun record(request: GitHubLookupRoute.Request, data: JsonObject): String? {
        val row = (data[if (request.route == GitHubLookupRoute.ISSUE) "issue" else "pull_request"] as? JsonObject) ?: data
        if (row.integer("number") != request.number || row.text("title").isBlank() || row.text("state").isBlank()) return null
        if (request.filesOnly) {
            val files = data["files"] as? JsonArray ?: return null
            val total = data.integer("files_total") ?: row.integer("changed_files") ?: return null
            if (data["files_truncated"] == JsonPrimitive(true) ||
                data["has_more"] == JsonPrimitive(true) ||
                total != files.size
            ) {
                return null
            }
            val rendered = files.map { value ->
                val file = value as? JsonObject ?: return null
                val path = file.text("filename").ifBlank { file.text("path") }.takeIf(String::isNotBlank) ?: return null
                if (request.patches) {
                    val patch = (file["patch"] as? JsonPrimitive)?.contentOrNull ?: return null
                    "### ${cell(path)}\n\n" + patch.lineSequence().joinToString("\n") { "    $it" }
                } else {
                    "- ${cell(path)} (${cell(file.text("status"))})"
                }
            }
            return "## PR #${request.number}: ${cell(row.text("title"))}\n\n" + rendered.joinToString("\n\n")
        }
        // Missing bodies and truncated bodies require ordinary source recovery.
        val body = row["body"] as? JsonPrimitive ?: return null
        if (!body.isString || row["body_truncated"] == JsonPrimitive(true)) return null
        val kind = if (request.route == GitHubLookupRoute.ISSUE) "Issue" else "PR"
        return "## $kind #${request.number}: ${cell(row.text("title"))}\n\nState: ${cell(row.text("state"))}.\n\n" +
            body.content.lineSequence().joinToString("\n") { "> $it" }
    }

    private fun cell(value: String) = value.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ')
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private val allowedArguments = setOf("action", "owner", "repo", "state", "sort", "direction", "per_page", "page", "ref", "issue_number", "pull_number", "include_files", "include_checks", "include_patches", "response_detail", "verify_live")
}
