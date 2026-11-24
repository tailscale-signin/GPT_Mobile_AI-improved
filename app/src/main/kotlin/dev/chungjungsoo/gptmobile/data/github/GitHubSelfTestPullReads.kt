package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A PR read follows its own observed head, independently of the selected workspace commit. */
internal object GitHubSelfTestPullReads {
    private val reads = setOf("headers", "context", "comments", "reviews", "threads")
    private val revision = Regex("[a-fA-F0-9]{40}")
    data class Selection(val row: JsonObject, val receipt: JsonObject)

    fun select(receipts: List<JsonObject>): Selection? {
        val selected = GitHubSelfTestPrerequisites.selected(receipts)
        val eligible = (selected?.receipts ?: receipts).withIndex().filter { (_, receipt) ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                receipt.text("tool").githubOperationFamily() == "github_pr"
        }.asReversed()
        val listed = eligible.flatMap { (index, receipt) ->
            if ((receipt["arguments"] as? JsonObject)?.text("action") != "list") return@flatMap emptyList()
            val root = GitHubInventoryObservation.planningRoot(receipt) ?: return@flatMap emptyList()
            ((root["pull_requests"] ?: root["items"]) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { row ->
                number(row["number"])?.let { Triple(index, receipt, JsonObject(row + ("number" to it))) }
            }
        }
        val initial = (listed.filter { it.third.text("state") == "open" } + listed).firstOrNull()
            ?: eligible.firstNotNullOfOrNull { (index, receipt) -> detail(receipt)?.let { Triple(index, receipt, it) } }
            ?: return null
        // Preserve the selected PR and connection. Only a newer observation of that
        // same resource can replace its head; an old list is not a revision lease.
        val latest = eligible.firstNotNullOfOrNull { (index, receipt) ->
            if (index < initial.first || !sameScope(receipt, initial.second) || receipt.text("tool") != initial.second.text("tool")) return@firstNotNullOfOrNull null
            val row = if ((receipt["arguments"] as? JsonObject)?.text("action") == "list") {
                listed.firstOrNull { it.first == index && it.third["number"] == initial.third["number"] }?.third
            } else {
                detail(receipt)?.takeIf { it["number"] == initial.third["number"] }
            }
            row?.takeIf { head(it) != null }?.let { Selection(it, receipt) }
        }
        return latest ?: Selection(initial.third, initial.second)
    }

    /** At most one fresh-header read per PR and one corrected read per failed action. */
    fun recoverReview(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): ProviderEvent.ToolCall? {
        val failed = receipts.lastOrNull { row ->
            row["trusted"] == JsonPrimitive(true) &&
                row["success"] == JsonPrimitive(false) &&
                row["verification_eligible"] != JsonPrimitive(false) &&
                row["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                row.text("tool").githubOperationFamily() == "github_pr" &&
                row.text("error_code") == "GITHUB_PR_HEAD_CHANGED" &&
                (row["arguments"] as? JsonObject)?.text("action") in reads
        } ?: return null
        val number = number((failed["arguments"] as? JsonObject)?.get("pull_number")) ?: return null
        val bound = receipts.filter { row ->
            sameScope(failed, row) && row.text("tool") == failed.text("tool") && (row["arguments"] as? JsonObject)?.get("pull_number") == number
        }
        val selected = select(bound) ?: Selection(JsonObject(mapOf("number" to number)), failed)
        return recover(tools, bound, selected).firstOrNull()
    }

    fun recover(tools: List<AgentToolDefinition>, receipts: List<JsonObject>, selected: Selection?): List<ProviderEvent.ToolCall> {
        if (selected == null) return emptyList()
        val source = selected.receipt
        if (source.text("binding").isBlank() || source.text("repository").isBlank()) return emptyList()
        val number = selected.row["number"] ?: return emptyList()
        val tool = tools.singleOrNull { it.modelVisible && it.name == source.text("tool") } ?: return emptyList()
        val observations = receipts.filter { receipt ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                receipt["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                sameScope(receipt, source) &&
                receipt.text("tool") == tool.name
        }
        return reads.mapNotNull { action ->
            val attempts = observations.filter {
                val args = it["arguments"] as? JsonObject
                args?.text("action") == action && args["pull_number"] == number && !it.text("call_id").startsWith("pr-head-refresh-")
            }
            val failed = attempts.lastOrNull() ?: return@mapNotNull null
            if (failed["success"] != JsonPrimitive(false) ||
                failed.text("error_code") != "GITHUB_PR_HEAD_CHANGED" ||
                failed.text("call_id").startsWith("pr-head-retry-") ||
                GitHubActionIdentity.digest(failed) == null
            ) {
                return@mapNotNull null
            }
            val previous = failed["arguments"] as? JsonObject ?: return@mapNotNull null
            val expected = (previous["expected_head_sha"] as? JsonPrimitive)?.takeIf { it.isString && revision.matches(it.content) }
            val key = GitHubEvidenceBook.digest("${source.text("binding")}|${source.text("repository")}|${tool.name}|$number|$action").take(24)
            if (observations.any { it.text("call_id") == "pr-head-retry-$key" || it.text("call_id").startsWith("pr-head-retry-$key-") }) return@mapNotNull null
            val current = head(selected.row)
            val call = if (expected == null) {
                recoverUnheadedContext(tool, observations, selected, failed, previous, "pr-head-retry-$key") ?: return@mapNotNull null
            } else if (current != null && !current.content.equals(expected.content, true)) {
                ProviderEvent.ToolCall("pr-head-retry-$key", tool.name, JsonObject(previous + ("expected_head_sha" to current)))
            } else {
                val id = "pr-head-refresh-" + GitHubEvidenceBook.digest("${source.text("binding")}|${source.text("repository")}|${tool.name}|$number").take(24)
                if (observations.any { it.text("call_id") == id || it.text("call_id").startsWith("$id-") }) return@mapNotNull null
                val arguments = previous.filterKeys { it in setOf("owner", "repo", "pull_number") } +
                    mapOf("action" to JsonPrimitive("headers"), "verify_live" to JsonPrimitive(true))
                ProviderEvent.ToolCall(id, tool.name, JsonObject(arguments))
            }
            call.takeIf { GitHubAuthorizationBoundary.isReplayableRead(it.name, it.arguments) && GitHubSelfTestReads.valid(tool, it.arguments) }
        }.distinctBy { it.callId }
    }

    /** Acquire a missing lease through this recovery's live header read. */
    private fun recoverUnheadedContext(
        tool: AgentToolDefinition,
        observations: List<JsonObject>,
        selected: Selection,
        failed: JsonObject,
        previous: JsonObject,
        retryId: String
    ): ProviderEvent.ToolCall? {
        if (previous.text("action") != "context" ||
            setOf("expected_head", "expected_head_sha", "expected_base_sha", "view_cursor").any { it in previous } ||
            !GitHubSelfTestReads.valid(tool, previous)
        ) {
            return null
        }
        val source = selected.receipt
        val number = selected.row.getValue("number")
        val id = "pr-head-refresh-" + GitHubEvidenceBook.digest("${source.text("binding")}|${source.text("repository")}|${tool.name}|$number").take(24)
        val arguments = JsonObject(
            previous.filterKeys { it in setOf("owner", "repo", "pull_number") } +
                mapOf("action" to JsonPrimitive("headers"), "verify_live" to JsonPrimitive(true))
        )
        val attempts = observations.withIndex().filter { (_, receipt) -> receipt.text("call_id") == id || receipt.text("call_id").startsWith("$id-") }
        if (attempts.isEmpty()) return ProviderEvent.ToolCall(id, tool.name, arguments)
        // The call ID bounds the refresh across restore; its exact arguments,
        // transport provenance and position establish the new observation.
        val refreshed = attempts.singleOrNull()?.takeIf { it.index > observations.lastIndexOf(failed) }?.value ?: return null
        if (refreshed["success"] != JsonPrimitive(true) ||
            GitHubEvidenceOrigin.of(refreshed) != "LIVE" ||
            !GitHubActionIdentity.matches(tool.name, arguments, refreshed)
        ) {
            return null
        }
        val fresh = detail(refreshed)?.let(::head) ?: return null
        // A later changed observation cannot renew this recovery's single lease.
        if (head(selected.row)?.content?.equals(fresh.content, true) != true) return null
        return ProviderEvent.ToolCall(
            retryId,
            tool.name,
            JsonObject(previous + mapOf("expected_head_sha" to fresh, "verify_live" to JsonPrimitive(true)))
        )
    }

    /** An earlier successful lease does not complete a read of the newly observed head. */
    fun observedAt(receipt: JsonObject, revision: JsonPrimitive): Boolean {
        val observed = detail(receipt)?.let(::head)
            ?: ((receipt["arguments"] as? JsonObject)?.get("expected_head_sha") as? JsonPrimitive)?.takeIf { it.isString && this.revision.matches(it.content) }
        return observed?.content?.equals(revision.content, true) == true
    }

    private fun detail(receipt: JsonObject): JsonObject? {
        val args = receipt["arguments"] as? JsonObject ?: return null
        if (args.text("action") !in reads + "create") return null
        val root = GitHubInventoryObservation.planningRoot(receipt) ?: return null
        val pull = root["pull_request"] as? JsonObject
        val returned = number(pull?.get("number") ?: root["pull_number"] ?: root["number"])
        val requested = number(args["pull_number"])
        if (returned != null && requested != null && returned != requested) return null
        val id = returned ?: requested ?: return null
        return JsonObject((pull ?: root).filterKeys { it in setOf("head", "head_sha", "state") } + ("number" to id))
    }

    private fun number(value: JsonElement?): JsonPrimitive? = (value as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }?.toIntOrNull()?.let(::JsonPrimitive)

    private fun head(row: JsonObject): JsonPrimitive? = (((row["head"] as? JsonObject)?.get("sha") ?: row["head_sha"]) as? JsonPrimitive)
        ?.takeIf { it.isString && revision.matches(it.content) }

    private fun sameScope(left: JsonObject, right: JsonObject): Boolean = left["binding"] == right["binding"] && left["repository"] == right["repository"]
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
